package com.example.thesis.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite verifying [SemanticVersion] parsing, precedence, and update availability evaluation.
 */
class SemanticVersionTest {

    @Test
    fun test_parse_standardSemanticVersion() {
        val version = SemanticVersion.parse("1.2.3")
        assertNotNull(version)
        assertEquals(1, version!!.major)
        assertEquals(2, version.minor)
        assertEquals(3, version.patch)
        assertNull(version.preRelease)
    }

    @Test
    fun test_parse_prefixedWithV_normalizesProperly() {
        val versionLower = SemanticVersion.parse("v1.0.4")
        val versionUpper = SemanticVersion.parse("V2.1.0")

        assertNotNull(versionLower)
        assertEquals(1, versionLower!!.major)
        assertEquals(0, versionLower.minor)
        assertEquals(4, versionLower.patch)

        assertNotNull(versionUpper)
        assertEquals(2, versionUpper!!.major)
        assertEquals(1, versionUpper.minor)
        assertEquals(0, versionUpper.patch)
    }

    @Test
    fun test_parse_withPreReleaseIdentifier() {
        val version = SemanticVersion.parse("v1.0.5-beta.1")
        assertNotNull(version)
        assertEquals(1, version!!.major)
        assertEquals(0, version.minor)
        assertEquals(5, version.patch)
        assertEquals("beta.1", version.preRelease)
    }

    @Test
    fun test_parse_invalidOrBlank_returnsNull() {
        assertNull(SemanticVersion.parse(""))
        assertNull(SemanticVersion.parse("   "))
        assertNull(SemanticVersion.parse("invalid"))
        assertNull(SemanticVersion.parse(null))
    }

    @Test
    fun test_compareTo_majorPrecedence() {
        val v1 = SemanticVersion.parse("2.0.0")!!
        val v2 = SemanticVersion.parse("1.9.9")!!
        assertTrue(v1 > v2)
    }

    @Test
    fun test_compareTo_minorPrecedence() {
        val v1 = SemanticVersion.parse("1.2.0")!!
        val v2 = SemanticVersion.parse("1.1.9")!!
        assertTrue(v1 > v2)
    }

    @Test
    fun test_compareTo_patchPrecedence() {
        val v1 = SemanticVersion.parse("1.0.5")!!
        val v2 = SemanticVersion.parse("1.0.4")!!
        assertTrue(v1 > v2)
    }

    @Test
    fun test_compareTo_fullReleasePrecedesPreRelease() {
        val full = SemanticVersion.parse("1.0.5")!!
        val pre = SemanticVersion.parse("1.0.5-rc1")!!
        assertTrue(full > pre)
    }

    @Test
    fun test_isUpdateAvailable_whenRemoteIsNewer_returnsTrue() {
        assertTrue(SemanticVersion.isUpdateAvailable("v1.0.5", "1.0.4"))
        assertTrue(SemanticVersion.isUpdateAvailable("1.1.0", "1.0.9"))
        assertTrue(SemanticVersion.isUpdateAvailable("2.0.0", "1.9.9"))
    }

    @Test
    fun test_isUpdateAvailable_whenSameOrOlder_returnsFalse() {
        // Equal
        assertFalse(SemanticVersion.isUpdateAvailable("v1.0.4", "1.0.4"))
        assertFalse(SemanticVersion.isUpdateAvailable("1.0.4", "v1.0.4"))

        // Remote older
        assertFalse(SemanticVersion.isUpdateAvailable("1.0.3", "1.0.4"))
        assertFalse(SemanticVersion.isUpdateAvailable("0.9.9", "1.0.0"))
    }

    @Test
    fun test_isUpdateAvailable_invalidStrings_returnsFalse() {
        assertFalse(SemanticVersion.isUpdateAvailable("not_a_version", "1.0.4"))
        assertFalse(SemanticVersion.isUpdateAvailable("1.0.5", "not_a_version"))
        assertFalse(SemanticVersion.isUpdateAvailable(null, "1.0.4"))
    }
}
