package com.example.thesis.diagnostics

import android.content.Context
import com.example.thesis.ui.ActionLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ActionLogSession(
    val sessionId: String,
    val sessionNumber: Int,
    val startedAt: Long,
    val endedAt: Long,
    val isCurrent: Boolean,
    val entries: List<ActionLogEntry>
) {
    val errorCount: Int get() = entries.count { it.isError }
    val hasCrash: Boolean get() = entries.any { it.tag.equals("CRASH", ignoreCase = true) }
}

// Almacen persistente que separa los registros por sesion cada vez que la aplicacion se cierra y vuelve a abrirse.
class ActionLogStore(
    private val logFile: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS
) {
    private val stateLock = Any()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ThesisActionLogWriter").apply { isDaemon = true }
    }

    @Volatile
    var currentSessionId: String = UUID.randomUUID().toString()
        private set

    @Volatile
    var currentSessionStartedAt: Long = System.currentTimeMillis()
        private set

    @Volatile
    private var currentSessionHasEntries: Boolean = false

    private val _entries: MutableStateFlow<List<ActionLogEntry>>
    val entries: StateFlow<List<ActionLogEntry>>

    private val _sessions: MutableStateFlow<List<ActionLogSession>>
    val sessions: StateFlow<List<ActionLogSession>>

    init {
        val allLoadedEntries = loadAllDecodedEntries(logFile)
        val initialEntries = trimEntries(
            entries = allLoadedEntries,
            maxEntries = maxEntries,
            maxSessions = maxSessions,
            activeSessionId = currentSessionId
        )
        registerActiveSessionForCrashHandler(currentSessionId, currentSessionStartedAt)
        _entries = MutableStateFlow(initialEntries)
        entries = _entries.asStateFlow()
        _sessions = MutableStateFlow(
            buildSessions(
                entries = initialEntries,
                currentSessionId = currentSessionId,
                currentSessionStartedAt = currentSessionStartedAt
            )
        )
        sessions = _sessions.asStateFlow()
        if (initialEntries.size < allLoadedEntries.size) {
            // Podamos el archivo en disco al iniciar para no conservar sesiones antiguas excedentes.
            runCatching { writer.execute { rewriteEntries(logFile, initialEntries) } }
        }
    }

    // Inicia una nueva sesion al reabrir la UI si la sesion previa ya contenia eventos, evitando sesiones vacias duplicadas en arranque en frio.
    fun onAppOpened(nowMs: Long = System.currentTimeMillis()): String {
        val sessionId: String
        val prunedEntries: List<ActionLogEntry>?
        synchronized(stateLock) {
            if (!currentSessionHasEntries) {
                currentSessionStartedAt = nowMs
                registerActiveSessionForCrashHandler(currentSessionId, currentSessionStartedAt)
                refreshSessionsLocked()
                return currentSessionId
            }
            val result = startNewSessionLocked(nowMs)
            sessionId = result.first
            prunedEntries = result.second
        }
        if (prunedEntries != null) {
            runCatching { writer.execute { rewriteEntries(logFile, prunedEntries) } }
        }
        return sessionId
    }

    fun startNewSession(nowMs: Long = System.currentTimeMillis()): String {
        val sessionId: String
        val prunedEntries: List<ActionLogEntry>?
        synchronized(stateLock) {
            val result = startNewSessionLocked(nowMs)
            sessionId = result.first
            prunedEntries = result.second
        }
        if (prunedEntries != null) {
            runCatching { writer.execute { rewriteEntries(logFile, prunedEntries) } }
        }
        return sessionId
    }

    private fun startNewSessionLocked(nowMs: Long): Pair<String, List<ActionLogEntry>?> {
        currentSessionId = UUID.randomUUID().toString()
        currentSessionStartedAt = nowMs
        currentSessionHasEntries = false
        registerActiveSessionForCrashHandler(currentSessionId, currentSessionStartedAt)
        val previousEntries = _entries.value
        val trimmed = trimEntries(
            entries = previousEntries,
            maxEntries = maxEntries,
            maxSessions = maxSessions,
            activeSessionId = currentSessionId
        )
        _entries.value = trimmed
        refreshSessionsLocked()
        val prunedToRewrite = if (trimmed.size < previousEntries.size) trimmed else null
        return currentSessionId to prunedToRewrite
    }

    fun append(entry: ActionLogEntry) {
        val boundEntry: ActionLogEntry
        val updatedSnapshot: List<ActionLogEntry>
        val sessionEvicted: Boolean
        synchronized(stateLock) {
            val sid = entry.sessionId.ifBlank { currentSessionId }
            val startedAt = if (entry.sessionStartedAt > 0L) {
                entry.sessionStartedAt
            } else if (sid == currentSessionId) {
                currentSessionStartedAt
            } else {
                entry.timestamp
            }
            if (sid == currentSessionId) {
                currentSessionHasEntries = true
            }
            boundEntry = entry.copy(
                sessionId = sid,
                sessionStartedAt = startedAt
            )
            val candidate = _entries.value + boundEntry
            val updated = trimEntries(
                entries = candidate,
                maxEntries = maxEntries,
                maxSessions = maxSessions,
                activeSessionId = currentSessionId
            )
            sessionEvicted = countDistinctSessions(updated) < countDistinctSessions(candidate)
            updatedSnapshot = updated
            _entries.value = updated
            refreshSessionsLocked()
        }
        if (sessionEvicted) {
            // Reescribimos el registro en disco para purgar de inmediato las sesiones que superan el limite.
            runCatching { writer.execute { rewriteEntries(logFile, updatedSnapshot) } }
        } else {
            val line = encodeLine(boundEntry)
            // Escribimos fuera del hilo principal para no bloquear la UI con E/S de disco.
            runCatching { writer.execute { appendLineAndCompact(logFile, line, maxEntries, maxSessions) } }
        }
    }

    fun clear() {
        synchronized(stateLock) {
            currentSessionId = UUID.randomUUID().toString()
            currentSessionStartedAt = System.currentTimeMillis()
            currentSessionHasEntries = false
            registerActiveSessionForCrashHandler(currentSessionId, currentSessionStartedAt)
            _entries.value = emptyList()
            refreshSessionsLocked()
        }
        runCatching {
            writer.execute {
                synchronized(FILE_LOCK) { runCatching { logFile.delete() } }
            }
        }
    }

    private fun refreshSessionsLocked() {
        _sessions.value = buildSessions(
            entries = _entries.value,
            currentSessionId = currentSessionId,
            currentSessionStartedAt = currentSessionStartedAt
        )
    }

    // Permite a las pruebas esperar a que terminen las escrituras asincronas pendientes.
    fun awaitPendingWrites(timeoutMs: Long = 2_000L) {
        runCatching { writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 1_000
        const val DEFAULT_MAX_SESSIONS = 5
        const val LEGACY_SESSION_ID = "legacy-session"
        private const val MAX_MESSAGE_CHARS = 8_000
        private const val COMPACT_THRESHOLD_BYTES = 768L * 1024L
        private const val FIELD_SEPARATOR = "\t"
        private val FILE_LOCK = Any()

        @Volatile
        private var lastActiveSessionId: String = ""

        @Volatile
        private var lastActiveSessionStartedAt: Long = 0L

        private fun registerActiveSessionForCrashHandler(sessionId: String, startedAt: Long) {
            lastActiveSessionId = sessionId
            lastActiveSessionStartedAt = startedAt
        }

        fun defaultFile(context: Context): File =
            File(context.applicationContext.filesDir, "diagnostics/action_logs.log")

        // Escritura sincrona usada por el manejador de fallos para asociar el crash a la sesion activa antes de morir el proceso.
        fun appendSync(
            file: File,
            entry: ActionLogEntry,
            maxEntries: Int = DEFAULT_MAX_ENTRIES,
            maxSessions: Int = DEFAULT_MAX_SESSIONS
        ) {
            val activeSid = entry.sessionId.ifBlank {
                lastActiveSessionId.ifBlank { UUID.randomUUID().toString() }
            }
            val activeStarted = when {
                entry.sessionStartedAt > 0L -> entry.sessionStartedAt
                lastActiveSessionStartedAt > 0L -> lastActiveSessionStartedAt
                else -> entry.timestamp
            }
            val bound = entry.copy(sessionId = activeSid, sessionStartedAt = activeStarted)
            appendLineAndCompact(file, encodeLine(bound), maxEntries, maxSessions)
        }

        internal fun buildSessions(
            entries: List<ActionLogEntry>,
            currentSessionId: String,
            currentSessionStartedAt: Long
        ): List<ActionLogSession> {
            val grouped = LinkedHashMap<String, MutableList<ActionLogEntry>>()
            for (entry in entries) {
                val sid = entry.sessionId.ifBlank { LEGACY_SESSION_ID }
                grouped.getOrPut(sid) { mutableListOf() }.add(entry)
            }
            if (!grouped.containsKey(currentSessionId)) {
                grouped[currentSessionId] = mutableListOf()
            }

            val total = grouped.size
            val chronological = ArrayList<ActionLogSession>(total)
            var index = 0
            for ((sid, sessionEntries) in grouped) {
                index++
                val isCurrent = sid == currentSessionId
                val startedAt = sessionEntries.firstOrNull()?.sessionStartedAt?.takeIf { it > 0L }
                    ?: sessionEntries.minOfOrNull { it.timestamp }
                    ?: if (isCurrent) currentSessionStartedAt else 0L
                val endedAt = sessionEntries.maxOfOrNull { it.timestamp } ?: startedAt
                chronological.add(
                    ActionLogSession(
                        sessionId = sid,
                        sessionNumber = index,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        isCurrent = isCurrent,
                        entries = sessionEntries.toList()
                    )
                )
            }
            // Mostramos primero la sesion actual y debajo las sesiones anteriores en orden inverso.
            return chronological.asReversed()
        }

        internal fun trimEntries(
            entries: List<ActionLogEntry>,
            maxEntries: Int,
            maxSessions: Int,
            activeSessionId: String? = null
        ): List<ActionLogEntry> {
            if (entries.isEmpty() || maxSessions <= 0) return emptyList()
            val sessionOrder = LinkedHashSet<String>()
            for (entry in entries) {
                sessionOrder.add(entry.sessionId.ifBlank { LEGACY_SESSION_ID })
            }
            val normalizedActive = activeSessionId?.takeIf { it.isNotBlank() }
            // Si la sesion activa aun no tiene registros, reservamos su hueco dentro del maximo permitido.
            val effectiveLimit = if (normalizedActive != null && normalizedActive !in sessionOrder) {
                (maxSessions - 1).coerceAtLeast(0)
            } else {
                maxSessions
            }
            if (effectiveLimit <= 0) return emptyList()
            val allowedSessions = if (sessionOrder.size > effectiveLimit) {
                sessionOrder.toList().takeLast(effectiveLimit).toSet()
            } else {
                sessionOrder
            }
            return entries
                .filter { (it.sessionId.ifBlank { LEGACY_SESSION_ID }) in allowedSessions }
                .takeLast(maxEntries)
        }

        private fun countDistinctSessions(entries: List<ActionLogEntry>): Int {
            if (entries.isEmpty()) return 0
            val sessions = HashSet<String>()
            for (entry in entries) {
                sessions.add(entry.sessionId.ifBlank { LEGACY_SESSION_ID })
            }
            return sessions.size
        }

        private fun rewriteEntries(file: File, entries: List<ActionLogEntry>) {
            synchronized(FILE_LOCK) {
                runCatching {
                    if (entries.isEmpty()) {
                        if (file.exists()) {
                            file.delete()
                        }
                        return@runCatching
                    }
                    file.parentFile?.mkdirs()
                    file.writeText(
                        entries.joinToString("\n", postfix = "\n") { encodeLine(it) },
                        StandardCharsets.UTF_8
                    )
                }
            }
        }

        internal fun loadEntries(file: File, maxEntries: Int): List<ActionLogEntry> =
            loadAllDecodedEntries(file).takeLast(maxEntries)

        private fun loadAllDecodedEntries(file: File): List<ActionLogEntry> {
            return synchronized(FILE_LOCK) {
                runCatching {
                    if (!file.exists()) return@runCatching emptyList()
                    val decoded = file.readLines(StandardCharsets.UTF_8)
                        .mapNotNull { decodeLine(it) }
                    val firstLegacyTs = decoded.firstOrNull { it.sessionId == LEGACY_SESSION_ID }?.timestamp ?: 0L
                    if (firstLegacyTs > 0L) {
                        decoded.map { entry ->
                            if (entry.sessionId == LEGACY_SESSION_ID && entry.sessionStartedAt == 0L) {
                                entry.copy(sessionStartedAt = firstLegacyTs)
                            } else {
                                entry
                            }
                        }
                    } else {
                        decoded
                    }
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
            val sid = encoder.encodeToString(
                entry.sessionId.ifBlank { LEGACY_SESSION_ID }.toByteArray(StandardCharsets.UTF_8)
            )
            val startedAt = (entry.sessionStartedAt.takeIf { it > 0L } ?: entry.timestamp).toString()
            return listOf(
                entry.timestamp.toString(),
                if (entry.isError) "1" else "0",
                entry.durationMs?.toString().orEmpty(),
                tag,
                message,
                id,
                sid,
                startedAt
            ).joinToString(FIELD_SEPARATOR)
        }

        internal fun decodeLine(line: String): ActionLogEntry? {
            if (line.isBlank()) return null
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size != 6 && parts.size != 8) return null
            return runCatching {
                val decoder = Base64.getDecoder()
                val timestamp = parts[0].toLong()
                // Mantenemos compatibilidad con lineas de 6 columnas guardadas por versiones previas.
                val sessionId = if (parts.size >= 8) {
                    String(decoder.decode(parts[6]), StandardCharsets.UTF_8).ifBlank { LEGACY_SESSION_ID }
                } else {
                    LEGACY_SESSION_ID
                }
                val sessionStartedAt = if (parts.size >= 8) {
                    parts[7].toLongOrNull() ?: timestamp
                } else {
                    0L
                }
                ActionLogEntry(
                    id = String(decoder.decode(parts[5]), StandardCharsets.UTF_8),
                    timestamp = timestamp,
                    tag = String(decoder.decode(parts[3]), StandardCharsets.UTF_8),
                    message = String(decoder.decode(parts[4]), StandardCharsets.UTF_8),
                    durationMs = parts[2].takeIf { it.isNotEmpty() }?.toDouble(),
                    isError = parts[1] == "1",
                    sessionId = sessionId,
                    sessionStartedAt = sessionStartedAt
                )
            }.getOrNull()
        }

        private fun appendLineAndCompact(
            file: File,
            line: String,
            maxEntries: Int,
            maxSessions: Int = DEFAULT_MAX_SESSIONS
        ) {
            synchronized(FILE_LOCK) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(line + "\n", StandardCharsets.UTF_8)
                    // Compactamos solo al superar el umbral para no reescribir el archivo en cada registro.
                    if (file.length() > COMPACT_THRESHOLD_BYTES) {
                        val decoded = file.readLines(StandardCharsets.UTF_8)
                            .mapNotNull { decodeLine(it) }
                        val kept = trimEntries(decoded, maxEntries, maxSessions)
                        if (kept.isEmpty()) {
                            file.delete()
                        } else {
                            file.writeText(
                                kept.joinToString("\n", postfix = "\n") { encodeLine(it) },
                                StandardCharsets.UTF_8
                            )
                        }
                    }
                }
            }
        }
    }
}
