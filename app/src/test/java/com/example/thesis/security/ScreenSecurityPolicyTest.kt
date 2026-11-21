package com.example.thesis.security

import androidx.compose.ui.window.SecureFlagPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSecurityPolicyTest {

    @Test
    fun test_dialogSecurityPolicy_isAlwaysSecureOn() {
        assertEquals(SecureFlagPolicy.SecureOn, ScreenSecurityPolicy.DIALOG_SECURE_POLICY)
        assertEquals(
            SecureFlagPolicy.SecureOn,
            ScreenSecurityManager.dialogProperties().securePolicy
        )
    }

    @Test
    fun test_dialogProperties_alwaysEnforcesSecureOnAcrossConfigurations() {
        val defaultProps = ScreenSecurityManager.dialogProperties()
        assertTrue(defaultProps.dismissOnBackPress)
        assertTrue(defaultProps.dismissOnClickOutside)
        assertEquals(SecureFlagPolicy.SecureOn, defaultProps.securePolicy)

        // Incluso con cierre deshabilitado durante descargas o acciones criticas, FLAG_SECURE se mantiene activo.
        val lockedProps = ScreenSecurityManager.dialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
        assertFalse(lockedProps.dismissOnBackPress)
        assertFalse(lockedProps.dismissOnClickOutside)
        assertEquals(SecureFlagPolicy.SecureOn, lockedProps.securePolicy)
    }

    @Test
    fun test_recordingVisibilityAndPrivacyShield_enforceShieldWhenRecordingVisible() {
        assertTrue(ScreenSecurityPolicy.isRecordingVisible(ScreenSecurityPolicy.RECORDING_STATE_VISIBLE))
        assertFalse(ScreenSecurityPolicy.isRecordingVisible(0))
        assertTrue(ScreenSecurityPolicy.shouldShowPrivacyShield(isScreenRecordingActive = true))
        assertFalse(ScreenSecurityPolicy.shouldShowPrivacyShield(isScreenRecordingActive = false))
    }
}
