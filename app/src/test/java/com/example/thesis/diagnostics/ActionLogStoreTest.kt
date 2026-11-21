package com.example.thesis.diagnostics

import com.example.thesis.ui.ActionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

class ActionLogStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun logFile(): File = File(tempFolder.root, "diagnostics/action_logs.log")

    @Test
    fun append_persistsEntriesAcrossInstances_andStartsNewSessionOnReopen() {
        val file = logFile()
        val firstRun = ActionLogStore(file)
        val firstSessionId = firstRun.currentSessionId

        firstRun.append(
            ActionLogEntry(
                tag = "CALL",
                message = "Σφάλμα\twith tab\nand newline",
                durationMs = 12.5,
                isError = true
            )
        )
        firstRun.append(ActionLogEntry(tag = "SYNC", message = "ok"))
        firstRun.awaitPendingWrites()

        // Al cerrar y volver a abrir el proceso se crea una nueva sesion y se conserva la anterior.
        val secondRun = ActionLogStore(file)
        val secondSessionId = secondRun.currentSessionId
        assertNotEquals(firstSessionId, secondSessionId)

        val sessionsBeforeSecondAppend = secondRun.sessions.value
        assertEquals(2, sessionsBeforeSecondAppend.size)
        assertTrue(sessionsBeforeSecondAppend[0].isCurrent)
        assertEquals(2, sessionsBeforeSecondAppend[0].sessionNumber)
        assertTrue(sessionsBeforeSecondAppend[0].entries.isEmpty())
        assertFalse(sessionsBeforeSecondAppend[1].isCurrent)
        assertEquals(1, sessionsBeforeSecondAppend[1].sessionNumber)
        assertEquals(2, sessionsBeforeSecondAppend[1].entries.size)

        secondRun.append(ActionLogEntry(tag = "MLS", message = "second run event"))
        secondRun.awaitPendingWrites()

        val sessionsAfterSecondAppend = secondRun.sessions.value
        assertEquals(2, sessionsAfterSecondAppend.size)
        assertEquals(listOf("second run event"), sessionsAfterSecondAppend[0].entries.map { it.message })
        assertEquals(listOf("CALL", "SYNC"), sessionsAfterSecondAppend[1].entries.map { it.tag })
    }

    @Test
    fun onAppOpened_startsNewSessionOnlyWhenCurrentSessionHasEntries() {
        val file = logFile()
        val store = ActionLogStore(file)
        val initialSid = store.currentSessionId

        // En arranque en frio (sin registros aun), onAppOpened reutiliza la sesion recien creada.
        assertEquals(initialSid, store.onAppOpened())
        assertEquals(1, store.sessions.value.size)

        store.append(ActionLogEntry(tag = "KEYSTORE", message = "init"))
        assertEquals(1, store.sessions.value.size)

        // Al cerrar la actividad y volver a abrirla, onAppOpened guarda la sesion 1 y abre la sesion 2.
        val nextSid = store.onAppOpened()
        assertNotEquals(initialSid, nextSid)
        assertEquals(2, store.sessions.value.size)
        assertTrue(store.sessions.value[0].isCurrent)
        assertEquals(2, store.sessions.value[0].sessionNumber)
        assertFalse(store.sessions.value[1].isCurrent)
        assertEquals(1, store.sessions.value[1].sessionNumber)
        assertEquals(1, store.sessions.value[1].entries.size)
    }

    @Test
    fun load_keepsOnlyMostRecentEntries() {
        val file = logFile()
        val store = ActionLogStore(file, maxEntries = 3)
        repeat(5) { i -> store.append(ActionLogEntry(tag = "T", message = "m$i")) }
        store.awaitPendingWrites()

        assertEquals(listOf("m2", "m3", "m4"), store.entries.value.map { it.message })
        val reloaded = ActionLogStore(file, maxEntries = 3).entries.value
        assertEquals(listOf("m2", "m3", "m4"), reloaded.map { it.message })
    }

    @Test
    fun clear_removesMemoryAndDiskState_andResetsToSingleSession() {
        val file = logFile()
        val store = ActionLogStore(file)
        store.append(ActionLogEntry(tag = "T", message = "secret"))
        store.onAppOpened()
        store.append(ActionLogEntry(tag = "T", message = "second"))
        store.awaitPendingWrites()
        assertTrue(file.exists())
        assertEquals(2, store.sessions.value.size)

        store.clear()
        store.awaitPendingWrites()
        assertTrue(store.entries.value.isEmpty())
        assertEquals(1, store.sessions.value.size)
        assertTrue(store.sessions.value[0].entries.isEmpty())
        assertFalse(file.exists())
        assertTrue(ActionLogStore(file).entries.value.isEmpty())
    }

    @Test
    fun appendSync_attachesCrashToActiveSession_andReopenStartsFreshSession() {
        val file = logFile()
        val activeRun = ActionLogStore(file)
        val crashedSessionId = activeRun.currentSessionId
        activeRun.append(ActionLogEntry(tag = "CALL", message = "starting call"))
        activeRun.awaitPendingWrites()

        val crash = CrashLogger.formatCrash("main", IllegalStateException("boom"))
        ActionLogStore.appendSync(file, ActionLogEntry(tag = "CRASH", message = crash, isError = true))

        val reopenedRun = ActionLogStore(file)
        val sessions = reopenedRun.sessions.value
        assertEquals(2, sessions.size)
        assertTrue(sessions[0].isCurrent)
        assertFalse(sessions[0].hasCrash)

        val previousCrashedSession = sessions[1]
        assertEquals(crashedSessionId, previousCrashedSession.sessionId)
        assertFalse(previousCrashedSession.isCurrent)
        assertTrue(previousCrashedSession.hasCrash)
        assertEquals(listOf("CALL", "CRASH"), previousCrashedSession.entries.map { it.tag })
        assertTrue(previousCrashedSession.entries.last().message.contains("IllegalStateException"))
    }

    @Test
    fun decodeLine_supportsLegacySixColumnLines_andIgnoresCorruptLines() {
        val enc = Base64.getEncoder()
        val legacyLine = listOf(
            "1700000000000",
            "0",
            "4.2",
            enc.encodeToString("SYNC".toByteArray(StandardCharsets.UTF_8)),
            enc.encodeToString("legacy entry".toByteArray(StandardCharsets.UTF_8)),
            enc.encodeToString("id-1".toByteArray(StandardCharsets.UTF_8))
        ).joinToString("\t")

        val decoded = ActionLogStore.decodeLine(legacyLine)
        assertEquals("SYNC", decoded?.tag)
        assertEquals("legacy entry", decoded?.message)
        assertEquals(ActionLogStore.LEGACY_SESSION_ID, decoded?.sessionId)

        assertNull(ActionLogStore.decodeLine("garbage"))
        assertNull(ActionLogStore.decodeLine("1\t0\t\t!!!\t!!!\t!!!"))
        assertNull(ActionLogStore.decodeLine(""))
    }

    @Test
    fun mainActivity_fragmentActivitySupports32BitPermissionRequestCodes() {
        // Verificamos que FragmentActivity provenga de androidx.fragment 1.8+ (sin checkForValidRequestCode de 16 bits).
        val legacyValidator = androidx.fragment.app.FragmentActivity::class.java.declaredMethods
            .firstOrNull { it.name == "checkForValidRequestCode" }
        assertNull(
            "FragmentActivity must not contain legacy 16-bit checkForValidRequestCode from fragment 1.2.x",
            legacyValidator
        )
    }
}
