package com.example.thesis.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local JVM unit tests for [DatabaseFactory] address normalization and path-traversal prevention.
 */
class DatabaseFactoryTest {

    /**
     * Requirement 1: Validate mixed-case addresses format properly to lowercase hex.
     */
    @Test
    fun test_addressSanitization_validAddressesNormalized() {
        val mixedCase = "0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B"
        val expected = "0xab5801a7d398351b8be11c439e05c5b3259aec9b"
        assertEquals(expected, DatabaseFactory.sanitiseEthereumAddress(mixedCase))

        val withWhitespace = "  0x4B20993Bc481177ec7E8f571ceCaE8A9e22C02db  "
        val expectedWhitespace = "0x4b20993bc481177ec7e8f571cecae8a9e22c02db"
        assertEquals(expectedWhitespace, DatabaseFactory.sanitiseEthereumAddress(withWhitespace))

        val allUpper = "0X71C7656EC7AB88B098DEFB751B7401B5F6D8976F"
        val expectedUpper = "0x71c7656ec7ab88b098defb751b7401b5f6d8976f"
        assertEquals(expectedUpper, DatabaseFactory.sanitiseEthereumAddress(allUpper))
    }

    /**
     * Requirement 2: Assert that inputs like "../../etc/passwd", null bytes, or invalid hex lengths return null and fail closed.
     */
    @Test
    fun test_addressSanitization_pathTraversalRejected() {
        // Classic path traversal payloads
        assertNull(DatabaseFactory.sanitiseEthereumAddress("../../etc/passwd"))
        assertNull(DatabaseFactory.sanitiseEthereumAddress("0x1234/../../evil"))
        assertNull(DatabaseFactory.sanitiseEthereumAddress("../xmtp_db.db"))

        // Null-byte injection payloads
        assertNull(DatabaseFactory.sanitiseEthereumAddress("0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B\u0000"))
        assertNull(DatabaseFactory.sanitiseEthereumAddress("0xAb5801a7\u0000D398351b8bE11C439e05C5B3259aeC9B"))

        // Missing prefix
        assertNull(DatabaseFactory.sanitiseEthereumAddress("Ab5801a7D398351b8bE11C439e05C5B3259aeC9B"))

        // Invalid lengths (too short / too long)
        assertNull(DatabaseFactory.sanitiseEthereumAddress("0x1234"))
        assertNull(DatabaseFactory.sanitiseEthereumAddress("0x" + "a".repeat(39)))
        assertNull(DatabaseFactory.sanitiseEthereumAddress("0x" + "a".repeat(41)))

        // Non-hex characters
        assertNull(DatabaseFactory.sanitiseEthereumAddress("0x" + "g".repeat(40)))
        assertNull(DatabaseFactory.sanitiseEthereumAddress("0x" + "z".repeat(40)))

        // Blank and empty strings
        assertNull(DatabaseFactory.sanitiseEthereumAddress(""))
        assertNull(DatabaseFactory.sanitiseEthereumAddress("   "))
    }
}
