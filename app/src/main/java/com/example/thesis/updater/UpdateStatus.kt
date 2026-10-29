package com.example.thesis.updater

import java.io.File

/**
 * Sealed hierarchy capturing the exhaustive lifecycle states of the In-App Update subsystem.
 */
sealed interface UpdateStatus {

    /**
     * Idle state: No active update verification or download is occurring.
     */
    data object Idle : UpdateStatus

    /**
     * Actively communicating with the remote GitHub API to verify the latest release manifest.
     */
    data object Checking : UpdateStatus

    /**
     * A newer release version was discovered on GitHub Releases.
     *
     * @property updateInfo Full descriptor of the available update including download URL and changelog.
     */
    data class UpdateAvailable(val updateInfo: AppUpdateInfo) : UpdateStatus

    /**
     * The running application binary is fully up to date with the remote release channel.
     *
     * @property currentVersion The active verified version name.
     */
    data class UpToDate(val currentVersion: String) : UpdateStatus

    /**
     * Active background streaming of the update APK file.
     *
     * @property progressPercent Percentage completion (0 - 100).
     * @property bytesDownloaded Number of bytes currently written to cache storage.
     * @property totalBytes      Total size in bytes reported by Content-Length header (-1 if unknown).
     * @property updateInfo      Reference to the update metadata.
     */
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val updateInfo: AppUpdateInfo
    ) : UpdateStatus

    /**
     * Download completed and SHA-verified; ready to invoke system package installer.
     *
     * @property apkFile    Cached APK file ready for package installer dispatch.
     * @property updateInfo Reference to the update metadata.
     */
    data class ReadyToInstall(
        val apkFile: File,
        val updateInfo: AppUpdateInfo
    ) : UpdateStatus

    /**
     * Non-fatal error during update query, network stream, or file I/O.
     *
     * @property message Descriptive, localized error explanation.
     * @property cause   Underlying root exception.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : UpdateStatus
}
