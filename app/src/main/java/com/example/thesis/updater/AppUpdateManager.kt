package com.example.thesis.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.thesis.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service managing in-app update discovery, download streaming, and package installation.
 * Uses GitHub Releases as the decentralized distribution ledger.
 */
class AppUpdateManager(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val appContext: Context = context.applicationContext

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    companion object {
        private const val TAG = "AppUpdateManager"
    }

    /**
     * Resolves the current running version string of the application.
     */
    fun getCurrentVersionName(): String {
        return try {
            BuildConfig.VERSION_NAME
        } catch (_: Throwable) {
            try {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.packageManager.getPackageInfo(
                        appContext.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                }
                packageInfo.versionName ?: "1.0.0"
            } catch (_: Throwable) {
                "1.0.0"
            }
        }
    }

    /**
     * Resets the update status to [UpdateStatus.Idle].
     */
    fun dismissUpdate() {
        _updateStatus.value = UpdateStatus.Idle
    }

    /**
     * Queries the GitHub Releases API to detect if a newer APK is available.
     *
     * @param manual Indicates if this check was explicitly triggered by user interaction.
     * @return [Result.success] with [AppUpdateInfo] or [Result.failure] on error.
     */
    suspend fun checkForUpdates(manual: Boolean = false): Result<AppUpdateInfo> = withContext(ioDispatcher) {
        _updateStatus.value = UpdateStatus.Checking
        val currentVersion = getCurrentVersionName()

        try {
            val endpoint = URL(UpdateConfig.LATEST_RELEASE_API_URL)
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
                readTimeout = UpdateConfig.READ_TIMEOUT_MS
                setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("GitHub API HTTP $responseCode: $errorBody")
            }

            val responseJson = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseJson)

            val rawTagName = root.optString("tag_name", "")
            val releaseTitle = root.optString("name", "Νέα Έκδοση")
            val releaseNotes = root.optString("body", "Δεν παρέχονται σημειώσεις έκδοσης.")
            val publishedAt = root.optString("published_at", "")

            val cleanTag = rawTagName.removePrefix("v").removePrefix("V")
            val assetsArray = root.optJSONArray("assets")

            var downloadUrl = ""
            var assetSize = 0L

            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.equals(UpdateConfig.TARGET_ASSET_NAME, ignoreCase = true) ||
                        assetName.endsWith(".apk", ignoreCase = true)
                    ) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        assetSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (downloadUrl.isBlank()) {
                throw NoSuchElementException("Δεν βρέθηκε διαθέσιμο αρχείο APK (.apk) στην τελευταία έκδοση ($rawTagName).")
            }

            val isAvailable = SemanticVersion.isUpdateAvailable(cleanTag, currentVersion)
            val updateInfo = AppUpdateInfo(
                latestVersion = cleanTag,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                assetSizeBytes = assetSize,
                publishedAt = publishedAt,
                isUpdateAvailable = isAvailable,
                currentVersion = currentVersion
            )

            if (isAvailable) {
                _updateStatus.value = UpdateStatus.UpdateAvailable(updateInfo)
                Log.i(TAG, "New update available: $cleanTag (current: $currentVersion)")
            } else {
                _updateStatus.value = UpdateStatus.UpToDate(currentVersion)
                Log.i(TAG, "Application is up to date: $currentVersion")
            }

            Result.success(updateInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            val errorMessage = "Αποτυχία ελέγχου ενημερώσεων: ${e.localizedMessage ?: e.message}"
            _updateStatus.value = UpdateStatus.Error(errorMessage, e)
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK update file into the application's isolated cache storage,
     * following cross-domain redirects (e.g., GitHub to AWS S3 CDN).
     * Emits real-time progress events through [_updateStatus].
     */
    suspend fun downloadUpdate(updateInfo: AppUpdateInfo): Result<File> = withContext(ioDispatcher) {
        val updatesDir = appContext.cacheDir.resolve("updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }

        // Clean up previous temporary update files
        updatesDir.listFiles()?.forEach { it.delete() }

        val destinationFile = updatesDir.resolve("thesis-update-${updateInfo.latestVersion}.apk")
        val tempFile = updatesDir.resolve("thesis-update-${updateInfo.latestVersion}.apk.tmp")

        try {
            _updateStatus.value = UpdateStatus.Downloading(
                progressPercent = 0,
                bytesDownloaded = 0L,
                totalBytes = updateInfo.assetSizeBytes,
                updateInfo = updateInfo
            )

            val connection = openConnectionFollowingRedirects(updateInfo.downloadUrl)
            val totalBytes = if (connection.contentLengthLong > 0) {
                connection.contentLengthLong
            } else {
                updateInfo.assetSizeBytes
            }

            var downloadedBytes = 0L
            val buffer = ByteArray(UpdateConfig.STREAM_BUFFER_SIZE_BYTES)

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val percent = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }

                        _updateStatus.value = UpdateStatus.Downloading(
                            progressPercent = percent,
                            bytesDownloaded = downloadedBytes,
                            totalBytes = totalBytes,
                            updateInfo = updateInfo
                        )
                    }
                    output.flush()
                }
            }

            if (tempFile.exists()) {
                if (tempFile.renameTo(destinationFile)) {
                    _updateStatus.value = UpdateStatus.ReadyToInstall(destinationFile, updateInfo)
                    Log.i(TAG, "Update downloaded successfully to ${destinationFile.absolutePath}")
                    Result.success(destinationFile)
                } else {
                    throw IllegalStateException("Αποτυχία οριστικοποίησης αρχείου ενημέρωσης.")
                }
            } else {
                throw IllegalStateException("Το αρχείο λήψης δεν δημιουργήθηκε.")
            }
        } catch (e: CancellationException) {
            tempFile.delete()
            destinationFile.delete()
            throw e
        } catch (e: Throwable) {
            tempFile.delete()
            destinationFile.delete()
            Log.e(TAG, "Update download failed: ${e.message}", e)
            val errorMsg = "Αποτυχία λήψης ενημέρωσης: ${e.localizedMessage ?: e.message}"
            _updateStatus.value = UpdateStatus.Error(errorMsg, e)
            Result.failure(e)
        }
    }

    /**
     * Checks if the app currently holds permission to request package installs on Android 8.0+.
     */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Navigates the user to Android system settings to grant unknown app install permission.
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }
    }

    /**
     * Triggers the Android system package installer via [FileProvider] and [Intent.ACTION_VIEW].
     */
    fun installUpdate(apkFile: File): Result<Unit> {
        return runCatching {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                throw IllegalStateException("Το αρχείο ενημέρωσης δεν βρέθηκε ή είναι κατεστραμμένο.")
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            appContext.startActivity(installIntent)
            Log.i(TAG, "Package installer intent dispatched for URI: $apkUri")
        }
    }

    /**
     * Robust HTTP connection helper that traverses HTTP 3xx redirect chains
     * (e.g. from GitHub asset endpoint to AWS S3 storage).
     */
    private fun openConnectionFollowingRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var hops = 0

        while (hops < UpdateConfig.MAX_REDIRECT_HOPS) {
            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
                readTimeout = UpdateConfig.READ_TIMEOUT_MS
                setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }

            when (connection.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect received without Location header.")
                    connection.disconnect()
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    hops++
                }
                HttpURLConnection.HTTP_OK -> return connection
                else -> {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    connection.disconnect()
                    throw IllegalStateException("HTTP ${connection.responseCode}: $err")
                }
            }
        }
        throw IllegalStateException("Υπέρβαση μέγιστου αριθμού ανακατευθύνσεων (${UpdateConfig.MAX_REDIRECT_HOPS}).")
    }
}
