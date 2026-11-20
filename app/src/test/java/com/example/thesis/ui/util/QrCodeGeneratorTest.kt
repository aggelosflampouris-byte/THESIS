package com.example.thesis.ui.util

import com.example.thesis.updater.UpdateConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeGeneratorTest {

    @Test
    fun test_encode_latestApkUrl_producesValidSquareMatrixWithFinderPatterns() {
        val matrix = QrCodeGenerator.encode(UpdateConfig.LATEST_APK_DOWNLOAD_URL)
        assertTrue("QR version 1+ must be at least 21x21", matrix.size >= 21)
        assertEquals("QR symbol side must obey 21 + 4*(v-1)", 0, (matrix.size - 21) % 4)

        assertFinderPattern(matrix, topRow = 0, leftCol = 0)
        assertFinderPattern(matrix, topRow = 0, leftCol = matrix.size - 7)
        assertFinderPattern(matrix, topRow = matrix.size - 7, leftCol = 0)

        val secondMatrix = QrCodeGenerator.encode(UpdateConfig.LATEST_APK_DOWNLOAD_URL)
        assertEquals("QR encoding must be deterministic for identical content", matrix, secondMatrix)
    }

    @Test
    fun test_encode_blankContent_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeGenerator.encode("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeGenerator.encode("   ")
        }
    }

    @Test
    fun test_encode_differentUrls_produceDistinctMatrices() {
        val apkMatrix = QrCodeGenerator.encode(UpdateConfig.LATEST_APK_DOWNLOAD_URL)
        val pageMatrix = QrCodeGenerator.encode(UpdateConfig.LATEST_RELEASE_PAGE_URL)
        assertNotEquals("Different URLs must encode to distinct QR matrices", apkMatrix, pageMatrix)
    }

    private fun assertFinderPattern(matrix: QrCodeMatrix, topRow: Int, leftCol: Int) {
        for (r in 0..6) {
            for (c in 0..6) {
                val isBorder = r == 0 || r == 6 || c == 0 || c == 6
                val isCenter = r in 2..4 && c in 2..4
                val dark = matrix.isDark(topRow + r, leftCol + c)
                if (isBorder || isCenter) {
                    assertTrue("Expected dark module at finder ($r,$c)", dark)
                } else {
                    assertFalse("Expected light module at finder ($r,$c)", dark)
                }
            }
        }
    }
}
