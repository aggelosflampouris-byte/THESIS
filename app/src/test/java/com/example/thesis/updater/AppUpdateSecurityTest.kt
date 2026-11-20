package com.example.thesis.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Security unit test suite for [AppUpdateManager] URL allowlisting, redirect downgrade
 * rejection, filename path-traversal sanitization, and SHA-256 digest parsing.
 */
class AppUpdateSecurityTest {

    @Test
    fun test_validateUpdateUrl_acceptsTrustedGitHubHttpsHosts() {
        val urls = listOf(
            "https://api.github.com/repos/aggelosflampouris-byte/THESIS/releases/latest",
            "https://github.com/aggelosflampouris-byte/THESIS/releases/download/v1.0.14/thesis-app-debug.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/app.apk",
            "https://release-assets.githubusercontent.com/foo/bar.apk"
        )
        urls.forEach { raw ->
            val parsed = AppUpdateManager.validateUpdateUrl(raw)
            assertEquals("https", parsed.protocol)
        }
    }

    @Test
    fun test_validateUpdateUrl_rejectsHttpDowngrade() {
        assertThrows(SecurityException::class.java) {
            AppUpdateManager.validateUpdateUrl("http://github.com/aggelosflampouris-byte/THESIS/app.apk")
        }
        assertThrows(SecurityException::class.java) {
            AppUpdateManager.validateUpdateUrl("http://objects.githubusercontent.com/app.apk")
        }
    }

    @Test
    fun test_validateUpdateUrl_rejectsUntrustedAndSpoofedDomains() {
        val badUrls = listOf(
            "https://evil.com/app.apk",
            "https://evilgithubusercontent.com/app.apk",
            "https://github.com.evil.com/app.apk",
            "https://api.github.com.attacker.org/releases/latest"
        )
        badUrls.forEach { raw ->
            assertThrows("Expected rejection for $raw", SecurityException::class.java) {
                AppUpdateManager.validateUpdateUrl(raw)
            }
        }
    }

    @Test
    fun test_sanitiseVersionForFilename_neutralizesTraversal() {
        assertEquals("1.0.15", AppUpdateManager.sanitiseVersionForFilename("1.0.15"))
        assertEquals("1.0.15-rc1", AppUpdateManager.sanitiseVersionForFilename("1.0.15-rc1"))

        val cleaned = AppUpdateManager.sanitiseVersionForFilename("../../etc/passwd")
        assertFalse("Path separator must not survive sanitization", cleaned.contains("/"))
        assertFalse("Dot-dot traversal sequence must not survive sanitization", cleaned.contains(".."))
        assertTrue(cleaned.isNotBlank())
    }

    @Test
    fun test_parseSha256Digest_parsesValidAndRejectsMalformed() {
        val hexUpper = "A".repeat(64)
        val hexLower = "a".repeat(64)
        assertEquals(hexLower, AppUpdateManager.parseSha256Digest("sha256:$hexUpper"))
        assertEquals(hexLower, AppUpdateManager.parseSha256Digest(hexLower))
        assertNull(AppUpdateManager.parseSha256Digest(null))
        assertNull(AppUpdateManager.parseSha256Digest(""))
        assertNull(AppUpdateManager.parseSha256Digest("sha256:short"))
        assertNull(AppUpdateManager.parseSha256Digest("sha256:" + "z".repeat(64)))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun test_checkForUpdates_silentModeKeepsIdleOnFailure_manualEmitsError() = runTest {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context

        val manager = AppUpdateManager(
            context = context,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            releaseApiUrl = "http://untrusted.invalid/releases"
        )

        val silentResult = manager.checkForUpdates(manual = false)
        assertTrue(silentResult.isFailure)
        assertEquals(
            "Silent startup failure must keep status Idle instead of opening error modal",
            UpdateStatus.Idle,
            manager.updateStatus.value
        )

        val manualResult = manager.checkForUpdates(manual = true)
        assertTrue(manualResult.isFailure)
        assertTrue(
            "Manual update check failure must expose UpdateStatus.Error",
            manager.updateStatus.value is UpdateStatus.Error
        )
    }

    @Test
    fun test_latestApkDownloadUrl_passesHttpsAllowlistValidation() {
        val apkUrl = AppUpdateManager.validateUpdateUrl(UpdateConfig.LATEST_APK_DOWNLOAD_URL)
        assertEquals("https", apkUrl.protocol)
        assertEquals("github.com", apkUrl.host)

        val pageUrl = AppUpdateManager.validateUpdateUrl(UpdateConfig.LATEST_RELEASE_PAGE_URL)
        assertEquals("https", pageUrl.protocol)
        assertEquals("github.com", pageUrl.host)
    }
}
