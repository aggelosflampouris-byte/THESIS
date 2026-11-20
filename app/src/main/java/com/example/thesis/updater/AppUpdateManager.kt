package com.example.thesis.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Service managing in-app update discovery, download streaming, integrity verification,
 * and package installation. Uses GitHub Releases as the distribution channel.
 */
class AppUpdateManager(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val releaseApiUrl: String = UpdateConfig.LATEST_RELEASE_API_URL
) {

    private val appContext: Context = context.applicationContext

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    companion object {
        private const val TAG = "AppUpdateManager"

        /**
         * Enforces HTTPS and restricts OTA URLs strictly to trusted GitHub hosts.
         */
        internal fun validateUpdateUrl(rawUrl: String): URL {
            val url = URL(rawUrl)
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw SecurityException("Non-HTTPS update URL rejected")
            }
            val host = url.host.lowercase(Locale.ROOT)
            val isTrustedHost = host.isNotBlank() && (
                host in UpdateConfig.ALLOWED_EXACT_HOSTS ||
                    host.endsWith(UpdateConfig.ALLOWED_CDN_HOST_SUFFIX)
                )
            if (!isTrustedHost) {
                throw SecurityException("Untrusted update host rejected: $host")
            }
            return url
        }

        /**
         * Sanitizes remote release version tags before interpolating into cache filenames.
         */
        internal fun sanitiseVersionForFilename(version: String): String {
            var sanitized = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
            while (sanitized.contains("..")) {
                sanitized = sanitized.replace("..", ".")
            }
            sanitized = sanitized.trim('.')
            require(sanitized.isNotBlank()) { "Invalid update version string for filename" }
            return sanitized
        }

        /**
         * Parses optional SHA-256 digest strings (e.g. `"sha256:<64-hex>"` or raw `<64-hex>`).
         */
        internal fun parseSha256Digest(rawDigest: String?): String? {
            if (rawDigest.isNullOrBlank()) return null
            val normalized = rawDigest.trim()
                .removePrefix("sha256:")
                .removePrefix("SHA256:")
                .trim()
                .lowercase(Locale.ROOT)
            return if (Regex("^[0-9a-f]{64}$").matches(normalized)) normalized else null
        }
    }

    /**
     * Resolves the current running version string of the application.
     */
    fun getCurrentVersionName(): String {
        return try {
            BuildConfig.VERSION_NAME
        } catch (_: Throwable) {
            try {
                getInstalledPackageInfo(flags = 0)?.versionName ?: "1.0.0"
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
        if (manual) {
            _updateStatus.value = UpdateStatus.Checking
        }
        val currentVersion = getCurrentVersionName()
        var connection: HttpURLConnection? = null

        try {
            val endpoint = validateUpdateUrl(releaseApiUrl)
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
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
            var expectedSha256: String? = null

            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.equals(UpdateConfig.TARGET_ASSET_NAME, ignoreCase = true) ||
                        assetName.endsWith(".apk", ignoreCase = true)
                    ) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        assetSize = asset.optLong("size", 0L)
                        expectedSha256 = parseSha256Digest(asset.optString("digest", ""))
                        break
                    }
                }
            }

            if (downloadUrl.isBlank()) {
                throw NoSuchElementException("Δεν βρέθηκε διαθέσιμο αρχείο APK (.apk) στην τελευταία έκδοση ($rawTagName).")
            }
            validateUpdateUrl(downloadUrl)

            val isAvailable = SemanticVersion.isUpdateAvailable(cleanTag, currentVersion)
            val updateInfo = AppUpdateInfo(
                latestVersion = cleanTag,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                assetSizeBytes = assetSize,
                publishedAt = publishedAt,
                isUpdateAvailable = isAvailable,
                currentVersion = currentVersion,
                expectedSha256 = expectedSha256
            )

            if (isAvailable) {
                _updateStatus.value = UpdateStatus.UpdateAvailable(updateInfo)
                Log.i(TAG, "New update available: $cleanTag (current: $currentVersion)")
            } else {
                _updateStatus.value = if (manual) UpdateStatus.UpToDate(currentVersion) else UpdateStatus.Idle
                Log.i(TAG, "Application is up to date: $currentVersion")
            }

            Result.success(updateInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Update check failed: ${e::class.java.simpleName}")
            if (manual) {
                val errorMessage = "Αποτυχία ελέγχου ενημερώσεων: ${e.localizedMessage ?: e.message}"
                _updateStatus.value = UpdateStatus.Error(errorMessage, e)
            } else if (_updateStatus.value !is UpdateStatus.UpdateAvailable) {
                _updateStatus.value = UpdateStatus.Idle
            }
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads the APK update file into the application's isolated cache storage,
     * enforces HTTPS + allowlisted redirects, verifies SHA-256 digest (when supplied)
     * and validates package identity + signing certificate match against the installed APK.
     */
    suspend fun downloadUpdate(updateInfo: AppUpdateInfo): Result<File> = withContext(ioDispatcher) {
        val updatesDir = appContext.cacheDir.resolve("updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }

        // Clean up previous temporary update files
        updatesDir.listFiles()?.forEach { it.delete() }

        val safeVersion = sanitiseVersionForFilename(updateInfo.latestVersion)
        val destinationFile = updatesDir.resolve("thesis-update-$safeVersion.apk")
        val tempFile = updatesDir.resolve("thesis-update-$safeVersion.apk.tmp")

        val canonicalBase = updatesDir.canonicalFile
        if (!destinationFile.canonicalFile.startsWith(canonicalBase) ||
            !tempFile.canonicalFile.startsWith(canonicalBase)
        ) {
            return@withContext Result.failure(
                SecurityException("Path traversal attempt detected in update filename")
            )
        }

        var connection: HttpURLConnection? = null
        try {
            _updateStatus.value = UpdateStatus.Downloading(
                progressPercent = 0,
                bytesDownloaded = 0L,
                totalBytes = updateInfo.assetSizeBytes,
                updateInfo = updateInfo
            )

            val activeConnection = openConnectionFollowingRedirects(updateInfo.downloadUrl)
            connection = activeConnection
            val totalBytes = if (activeConnection.contentLengthLong > 0) {
                activeConnection.contentLengthLong
            } else {
                updateInfo.assetSizeBytes
            }

            var downloadedBytes = 0L
            val buffer = ByteArray(UpdateConfig.STREAM_BUFFER_SIZE_BYTES)
            val sha256 = MessageDigest.getInstance("SHA-256")

            activeConnection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        sha256.update(buffer, 0, bytesRead)
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

            if (!tempFile.exists()) {
                throw IllegalStateException("Το αρχείο λήψης δεν δημιουργήθηκε.")
            }

            val actualSha256Hex = sha256.digest().joinToString("") { "%02x".format(it) }
            val expectedSha256 = updateInfo.expectedSha256?.lowercase(Locale.ROOT)
            if (expectedSha256 != null) {
                val validDigest = MessageDigest.isEqual(
                    actualSha256Hex.toByteArray(Charsets.UTF_8),
                    expectedSha256.toByteArray(Charsets.UTF_8)
                )
                if (!validDigest) {
                    throw SecurityException("Η επαλήθευση SHA-256 του APK απέτυχε.")
                }
            }

            verifyDownloadedApkSignature(tempFile)

            if (tempFile.renameTo(destinationFile)) {
                _updateStatus.value = UpdateStatus.ReadyToInstall(destinationFile, updateInfo)
                Log.i(TAG, "Update downloaded and verified (version=${updateInfo.latestVersion})")
                Result.success(destinationFile)
            } else {
                throw IllegalStateException("Αποτυχία οριστικοποίησης αρχείου ενημέρωσης.")
            }
        } catch (e: CancellationException) {
            tempFile.delete()
            destinationFile.delete()
            throw e
        } catch (e: Throwable) {
            tempFile.delete()
            destinationFile.delete()
            Log.e(TAG, "Update download failed: ${e::class.java.simpleName}")
            val errorMsg = "Αποτυχία λήψης ενημέρωσης: ${e.localizedMessage ?: e.message}"
            _updateStatus.value = UpdateStatus.Error(errorMsg, e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
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
            Log.i(TAG, "Package installer intent dispatched")
        }
    }

    private fun verifyDownloadedApkSignature(apkFile: File) {
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = getArchivePackageInfo(apkFile, signingFlags)
            ?: throw SecurityException("Μη έγκυρο ή κατεστραμμένο πακέτο APK.")

        if (archiveInfo.packageName != appContext.packageName) {
            throw SecurityException("Αναντιστοιχία αναγνωριστικού πακέτου APK.")
        }

        val installedInfo = getInstalledPackageInfo(signingFlags)
            ?: throw SecurityException("Αδυναμία ανάγνωσης πιστοποιητικού εγκατεστημένης εφαρμογής.")

        val archiveCerts = extractCertSha256Set(archiveInfo)
        val installedCerts = extractCertSha256Set(installedInfo)
        if (archiveCerts.isEmpty() || installedCerts.none { it in archiveCerts }) {
            throw SecurityException("Η ψηφιακή υπογραφή του APK δεν ταιριάζει με την εγκατεστημένη εφαρμογή.")
        }
    }

    private fun getInstalledPackageInfo(flags: Int): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, flags)
        }
    }

    private fun getArchivePackageInfo(apkFile: File, flags: Int): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
        }
    }

    private fun extractCertSha256Set(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo.signingInfo ?: return emptySet()
            val certs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            certs?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.toList().orEmpty()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            digest.reset()
            digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /**
     * Traverses HTTPS-only HTTP 3xx redirect chains within the allowlisted hosts
     * (e.g. from GitHub release asset URLs to GitHub CDN storage).
     */
    private fun openConnectionFollowingRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var hops = 0

        while (hops < UpdateConfig.MAX_REDIRECT_HOPS) {
            val url = validateUpdateUrl(currentUrl)
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
                        ?: run {
                            connection.disconnect()
                            throw IllegalStateException("Redirect received without Location header.")
                        }
                    connection.disconnect()
                    currentUrl = URL(url, location).toString()
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
