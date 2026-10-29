package com.example.thesis.updater

/**
 * Configuration constants for the GitHub Releases In-App Update pipeline.
 */
object UpdateConfig {
    const val GITHUB_OWNER = "aggelosflampouris-byte"
    const val GITHUB_REPO = "THESIS"

    /**
     * GitHub REST API v3 endpoint for the latest published release.
     */
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /**
     * User-Agent header required by GitHub API to prevent rate-limiting and connection drops.
     */
    const val USER_AGENT = "Thesis-Android-App-Updater/1.0"

    /**
     * Target APK asset name matching release artifacts created by GitHub Actions.
     */
    const val TARGET_ASSET_NAME = "thesis-app-debug.apk"

    /**
     * Network connect timeout in milliseconds.
     */
    const val CONNECT_TIMEOUT_MS = 15_000

    /**
     * Network read/stream timeout in milliseconds.
     */
    const val READ_TIMEOUT_MS = 45_000

    /**
     * Maximum redirect hops followed before failing closed.
     */
    const val MAX_REDIRECT_HOPS = 5

    /**
     * Buffer chunk size for APK stream writes.
     */
    const val STREAM_BUFFER_SIZE_BYTES = 16_384
}
