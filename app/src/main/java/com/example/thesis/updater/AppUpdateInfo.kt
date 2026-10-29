package com.example.thesis.updater

/**
 * Immutable metadata representing a discovered application update from the remote release channel.
 *
 * @property latestVersion       Semantic version tag of the latest release (e.g. "1.0.5").
 * @property releaseTitle         Human-readable release title published on GitHub.
 * @property releaseNotes         Markdown changelog or summary notes describing the changes.
 * @property downloadUrl          Direct download URL for the packaged `.apk` asset.
 * @property assetSizeBytes       Reported size of the APK asset in bytes (0 if unknown).
 * @property publishedAt          ISO 8601 publication timestamp from the release ledger.
 * @property isUpdateAvailable    Flag indicating if [latestVersion] > [currentVersion].
 * @property currentVersion       Local version currently running in the application process.
 */
data class AppUpdateInfo(
    val latestVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val assetSizeBytes: Long = 0L,
    val publishedAt: String = "",
    val isUpdateAvailable: Boolean,
    val currentVersion: String
)
