package com.example.thesis.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReportExporterTest {

    @Test
    fun test_writeReportFile_prunesPreviousReportsAndPreservesFullUtf8Content() {
        val tempRoot = Files.createTempDirectory("report-exporter-test").toFile()
        try {
            val reportsDir = tempRoot.resolve("reports")
            val staleFile = ReportExporter.writeReportFile(
                reportsDir = reportsDir,
                reportContent = "# Old Report",
                timestampMs = 1000L
            )
            assertTrue(staleFile.exists())

            val expectedContent = "# XMTP v4 (MLS) Report\nΕλληνικά & SHA-256"
            val latestFile = ReportExporter.writeReportFile(
                reportsDir = reportsDir,
                reportContent = expectedContent,
                timestampMs = 2000L
            )

            assertFalse("Stale report files must be deleted on new export", staleFile.exists())
            assertTrue(latestFile.exists())
            assertEquals(expectedContent, latestFile.readText(Charsets.UTF_8))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun test_layoutReportLinesForPdf_parsesHeadingsTablesCodeAndWrapsLongLines() {
        val md = """
            # Main Verification Title
            ## Cryptographic Proof Table
            | Check | Status | Details |
            ```text
            Address : 0x1234567890abcdef
            ```
            Αυτό είναι ένα πολύ μεγάλο κείμενο αναφοράς στα ελληνικά για έλεγχο αναδίπλωσης γραμμών σε PDF.
        """.trimIndent()

        val laidOut = ReportExporter.layoutReportLinesForPdf(md, maxBodyChars = 35, maxMonoChars = 25)
        assertEquals(PdfLineStyle.TITLE, laidOut.first().style)
        assertTrue(laidOut.any { it.style == PdfLineStyle.HEADING && it.text == "Cryptographic Proof Table" })
        assertTrue(laidOut.any { it.style == PdfLineStyle.MONOSPACE && it.text.contains("0x1234567890abcdef") })
        assertTrue(laidOut.count { it.style == PdfLineStyle.BODY && it.text.isNotEmpty() } >= 2)
    }

    @Test
    fun test_purgeCachedReports_deletesCachedReportFilesAndDirectory() {
        val cacheDir = Files.createTempDirectory("report-purge-test").toFile()
        try {
            val reportsDir = cacheDir.resolve(ReportExporter.REPORTS_DIR_NAME)
            val mdFile = ReportExporter.writeReportFile(reportsDir, "Sensitive PoC", timestampMs = 3000L)
            val pdfDummy = reportsDir.resolve("thesis-verification-report-3000.pdf").apply { writeText("PDF") }
            assertTrue(mdFile.exists())
            assertTrue(pdfDummy.exists())

            ReportExporter.purgeCachedReports(cacheDir)

            assertFalse("Cached report file must be deleted on GDPR purge", mdFile.exists())
            assertFalse("Cached PDF report file must be deleted on GDPR purge", pdfDummy.exists())
            assertFalse("Reports cache directory must be removed on purge", reportsDir.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
