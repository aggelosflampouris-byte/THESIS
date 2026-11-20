package com.example.thesis.security

import androidx.compose.ui.window.SecureFlagPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSecurityPolicyTest {

    @Test
    fun test_defaultScreenProtectionIsEnabledFailSafe() {
        assertTrue(ScreenSecurityPolicy.DEFAULT_SCREEN_PROTECTION_ENABLED)
        val store = InMemoryScreenSecurityStorage()
        assertTrue(store.isScreenProtectionEnabled.value)
    }

    @Test
    fun test_resolveDialogSecurePolicy_mapsBooleanToComposeWindowPolicy() {
        assertEquals(SecureFlagPolicy.SecureOn, ScreenSecurityPolicy.resolveDialogSecurePolicy(true))
        assertEquals(SecureFlagPolicy.SecureOff, ScreenSecurityPolicy.resolveDialogSecurePolicy(false))
    }

    @Test
    fun test_recordingShieldAndWarningBannerStates() {
        assertTrue(ScreenSecurityPolicy.isRecordingVisible(ScreenSecurityPolicy.RECORDING_STATE_VISIBLE))
        assertFalse(ScreenSecurityPolicy.isRecordingVisible(0))

        assertTrue(ScreenSecurityPolicy.shouldShowPrivacyShield(isProtectionEnabled = true, isScreenRecordingActive = true))
        assertFalse(ScreenSecurityPolicy.shouldShowPrivacyShield(isProtectionEnabled = false, isScreenRecordingActive = true))
        assertFalse(ScreenSecurityPolicy.shouldShowPrivacyShield(isProtectionEnabled = true, isScreenRecordingActive = false))

        assertTrue(ScreenSecurityPolicy.shouldShowRecordingWarningBanner(isProtectionEnabled = false, isScreenRecordingActive = true))
        assertFalse(ScreenSecurityPolicy.shouldShowRecordingWarningBanner(isProtectionEnabled = true, isScreenRecordingActive = true))
        assertFalse(ScreenSecurityPolicy.shouldShowRecordingWarningBanner(isProtectionEnabled = false, isScreenRecordingActive = false))
    }

    @Test
    fun test_inMemoryStorage_togglesAndResetsToProtectedOnGdprWipe() {
        val store = InMemoryScreenSecurityStorage()
        store.setScreenProtectionEnabled(false)
        assertFalse(store.isScreenProtectionEnabled.value)

        store.resetToDefault()
        assertTrue(store.isScreenProtectionEnabled.value)
    }
}
