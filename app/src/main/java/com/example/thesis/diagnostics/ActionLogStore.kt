package com.example.thesis.diagnostics

import android.content.Context
import com.example.thesis.ui.ActionLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Almacen persistente de registros en el directorio privado de la app para conservarlos entre reinicios y fallos.
class ActionLogStore(
    private val logFile: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ThesisActionLogWriter").apply { isDaemon = true }
    }

    private val _entries = MutableStateFlow(loadEntries(logFile, maxEntries))
    val entries: StateFlow<List<ActionLogEntry>> = _entries.asStateFlow()

    fun append(entry: ActionLogEntry) {
        _entries.update { current -> (current + entry).takeLast(maxEntries) }
        val line = encodeLine(entry)
        // Escribimos fuera del hilo principal para no bloquear la UI con E/S de disco.
        runCatching { writer.execute { appendLineAndCompact(logFile, line, maxEntries) } }
    }

    fun clear() {
        _entries.value = emptyList()
        runCatching {
            writer.execute {
                synchronized(FILE_LOCK) { runCatching { logFile.delete() } }
            }
        }
    }

    // Permite a las pruebas esperar a que terminen las escrituras asincronas pendientes.
    fun awaitPendingWrites(timeoutMs: Long = 2_000L) {
        runCatching { writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 500
        private const val MAX_MESSAGE_CHARS = 8_000
        private const val COMPACT_THRESHOLD_BYTES = 512L * 1024L
        private const val FIELD_SEPARATOR = "\t"
        private val FILE_LOCK = Any()

        fun defaultFile(context: Context): File =
            File(context.applicationContext.filesDir, "diagnostics/action_logs.log")

        // Escritura sincrona usada por el manejador de fallos, ya que el proceso muere justo despues.
        fun appendSync(file: File, entry: ActionLogEntry, maxEntries: Int = DEFAULT_MAX_ENTRIES) {
            appendLineAndCompact(file, encodeLine(entry), maxEntries)
        }

        internal fun loadEntries(file: File, maxEntries: Int): List<ActionLogEntry> {
            return synchronized(FILE_LOCK) {
                runCatching {
                    if (!file.exists()) return@runCatching emptyList()
                    file.readLines(StandardCharsets.UTF_8)
                        .mapNotNull { decodeLine(it) }
                        .takeLast(maxEntries)
                }.getOrDefault(emptyList())
            }
        }

        internal fun encodeLine(entry: ActionLogEntry): String {
            val encoder = Base64.getEncoder()
            val tag = encoder.encodeToString(entry.tag.toByteArray(StandardCharsets.UTF_8))
            val message = encoder.encodeToString(
                entry.message.take(MAX_MESSAGE_CHARS).toByteArray(StandardCharsets.UTF_8)
            )
            val id = encoder.encodeToString(entry.id.toByteArray(StandardCharsets.UTF_8))
            return listOf(
                entry.timestamp.toString(),
                if (entry.isError) "1" else "0",
                entry.durationMs?.toString().orEmpty(),
                tag,
                message,
                id
            ).joinToString(FIELD_SEPARATOR)
        }

        internal fun decodeLine(line: String): ActionLogEntry? {
            if (line.isBlank()) return null
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size != 6) return null
            return runCatching {
                val decoder = Base64.getDecoder()
                ActionLogEntry(
                    id = String(decoder.decode(parts[5]), StandardCharsets.UTF_8),
                    timestamp = parts[0].toLong(),
                    tag = String(decoder.decode(parts[3]), StandardCharsets.UTF_8),
                    message = String(decoder.decode(parts[4]), StandardCharsets.UTF_8),
                    durationMs = parts[2].takeIf { it.isNotEmpty() }?.toDouble(),
                    isError = parts[1] == "1"
                )
            }.getOrNull()
        }

        private fun appendLineAndCompact(file: File, line: String, maxEntries: Int) {
            synchronized(FILE_LOCK) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(line + "\n", StandardCharsets.UTF_8)
                    // Compactamos solo al superar el umbral para no reescribir el archivo en cada registro.
                    if (file.length() > COMPACT_THRESHOLD_BYTES) {
                        val kept = file.readLines(StandardCharsets.UTF_8)
                            .filter { it.isNotBlank() }
                            .takeLast(maxEntries)
                        file.writeText(kept.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
                    }
                }
            }
        }
    }
}
