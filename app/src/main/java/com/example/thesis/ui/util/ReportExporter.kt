package com.example.thesis.ui.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

internal enum class PdfLineStyle {
    TITLE,
    HEADING,
    MONOSPACE,
    BODY
}

internal data class PdfRenderLine(
    val text: String,
    val style: PdfLineStyle
)

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

    // Convierte texto Markdown en lineas estilizadas y ajustadas al ancho de pagina A4 sin dependencias Android para pruebas JVM.
    internal fun layoutReportLinesForPdf(
        markdown: String,
        maxBodyChars: Int = 86,
        maxMonoChars: Int = 78
    ): List<PdfRenderLine> {
        val out = ArrayList<PdfRenderLine>()
        var inCodeFence = false
        for (rawLine in markdown.lines()) {
            val trimmed = rawLine.trimEnd()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                continue
            }
            if (trimmed.isBlank()) {
                out.add(PdfRenderLine("", PdfLineStyle.BODY))
                continue
            }
            when {
                inCodeFence || trimmed.startsWith("|") -> {
                    wrapLine(trimmed, maxMonoChars).forEach { chunk ->
                        out.add(PdfRenderLine(chunk, PdfLineStyle.MONOSPACE))
                    }
                }
                trimmed.startsWith("# ") -> {
                    wrapLine(trimmed.removePrefix("# ").trim(), maxBodyChars).forEach { chunk ->
                        out.add(PdfRenderLine(chunk, PdfLineStyle.TITLE))
                    }
                }
                trimmed.startsWith("## ") || trimmed.startsWith("### ") -> {
                    val clean = trimmed.trimStart('#').trim()
                    wrapLine(clean, maxBodyChars).forEach { chunk ->
                        out.add(PdfRenderLine(chunk, PdfLineStyle.HEADING))
                    }
                }
                trimmed == "---" -> {
                    out.add(PdfRenderLine("----------------------------------------------------------------", PdfLineStyle.MONOSPACE))
                }
                else -> {
                    val plain = trimmed
                        .replace("**", "")
                        .replace("`", "")
                    wrapLine(plain, maxBodyChars).forEach { chunk ->
                        out.add(PdfRenderLine(chunk, PdfLineStyle.BODY))
                    }
                }
            }
        }
        return out
    }

    private fun wrapLine(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val wrapped = ArrayList<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            val splitAt = remaining.lastIndexOf(' ', startIndex = maxChars)
            val breakIdx = if (splitAt > 0) splitAt else maxChars
            wrapped.add(remaining.substring(0, breakIdx).trimEnd())
            remaining = remaining.substring(breakIdx).trimStart()
        }
        if (remaining.isNotEmpty()) {
            wrapped.add(remaining)
        }
        return wrapped
    }

    fun writePdfReportFile(
        reportsDir: File,
        reportTitle: String,
        reportMarkdown: String,
        timestampMs: Long = System.currentTimeMillis()
    ): File {
        if (!reportsDir.exists()) {
            reportsDir.mkdirs()
        }
        // Eliminamos PDFs previos para conservar solo el informe mas reciente y cumplir la higiene GDPR.
        reportsDir.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.name.endsWith(".pdf")) {
                existing.delete()
            }
        }
        val targetFile = reportsDir.resolve("thesis-verification-report-$timestampMs.pdf")
        val styledLines = layoutReportLinesForPdf(reportMarkdown)

        val pageWidth = 595
        val pageHeight = 842
        val marginX = 36f
        val topY = 44f
        val bottomY = pageHeight - 44f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 60, 120)
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 8f
            typeface = Typeface.MONOSPACE
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        fun lineStep(style: PdfLineStyle): Float = when (style) {
            PdfLineStyle.TITLE -> 20f
            PdfLineStyle.HEADING -> 16f
            PdfLineStyle.MONOSPACE -> 11.5f
            PdfLineStyle.BODY -> 13f
        }

        // Paginamos primero en memoria para imprimir el numero total exacto de paginas en cada pie.
        val pages = ArrayList<List<PdfRenderLine>>()
        var currentPage = ArrayList<PdfRenderLine>()
        var cursorY = topY
        for (line in styledLines) {
            val step = if (line.text.isEmpty()) 8f else lineStep(line.style)
            if (cursorY + step > bottomY && currentPage.isNotEmpty()) {
                pages.add(currentPage)
                currentPage = ArrayList()
                cursorY = topY
            }
            currentPage.add(line)
            cursorY += step
        }
        if (currentPage.isNotEmpty() || pages.isEmpty()) {
            pages.add(currentPage)
        }

        val pdfDocument = PdfDocument()
        try {
            val totalPages = pages.size
            pages.forEachIndexed { pageIndex, pageLines ->
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                var y = topY
                for (line in pageLines) {
                    if (line.text.isEmpty()) {
                        y += 8f
                        continue
                    }
                    val paint = when (line.style) {
                        PdfLineStyle.TITLE -> titlePaint
                        PdfLineStyle.HEADING -> headingPaint
                        PdfLineStyle.MONOSPACE -> monoPaint
                        PdfLineStyle.BODY -> bodyPaint
                    }
                    canvas.drawText(line.text, marginX, y, paint)
                    y += lineStep(line.style)
                }
                canvas.drawText(
                    "$reportTitle — Page ${pageIndex + 1}/$totalPages",
                    marginX,
                    pageHeight - 20f,
                    footerPaint
                )
                pdfDocument.finishPage(page)
            }
            FileOutputStream(targetFile).use { fos ->
                pdfDocument.writeTo(fos)
                fos.fd.sync()
            }
        } finally {
            pdfDocument.close()
        }
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

    fun sharePdfReport(
        context: Context,
        reportTitle: String,
        reportMarkdown: String
    ): Result<File> {
        return runCatching {
            val reportsDir = context.cacheDir.resolve(REPORTS_DIR_NAME)
            val pdfFile = writePdfReportFile(reportsDir, reportTitle, reportMarkdown)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$reportTitle (.pdf)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Εκτύπωση / Κοινοποίηση Αναφοράς (.pdf)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            pdfFile
        }
    }
}
