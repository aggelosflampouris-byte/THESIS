package com.example.thesis.ui.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

internal object ReportExporter {
    const val REPORTS_DIR_NAME = "reports"

    fun writeReportFile(
        reportsDir: File,
        reportContent: String,
        timestampMs: Long = System.currentTimeMillis()
    ): File {
        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }
        // Eliminamos reportes previos para evitar residuos de sesiones anteriores tras un borrado GDPR.
        reportsDir.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.name.endsWith(".md")) {
                existing.delete()
            }
        }
        val targetFile = reportsDir.resolve("thesis-poc-report-$timestampMs.md")
        targetFile.writeText(reportContent, Charsets.UTF_8)
        return targetFile
    }

    fun purgeCachedReports(cacheDir: File) {
        runCatching {
            val reportsDir = cacheDir.resolve(REPORTS_DIR_NAME)
            if (reportsDir.exists()) {
                reportsDir.listFiles()?.forEach { it.delete() }
                reportsDir.delete()
            }
        }
    }

    fun shareMarkdownReport(context: Context, reportContent: String): Result<File> {
        return runCatching {
            val reportsDir = context.cacheDir.resolve(REPORTS_DIR_NAME)
            val reportFile = writeReportFile(reportsDir, reportContent)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                reportFile
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "THESIS XMTP MLS PoC & Benchmark Report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Κοινοποίηση Αναφοράς (.md)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            reportFile
        }
    }
}
