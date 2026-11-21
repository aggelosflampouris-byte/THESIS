package com.example.thesis.diagnostics

import com.example.thesis.ui.ActionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ActionLogStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun logFile(): File = File(tempFolder.root, "diagnostics/action_logs.log")

    @Test
    fun append_persistsEntriesAcrossInstances_includingSpecialCharacters() {
        val file = logFile()
        val store = ActionLogStore(file)
        val entry = ActionLogEntry(
            tag = "CALL",
            message = "Σφάλμα\twith tab\nand newline",
            durationMs = 12.5,
            isError = true
        )
        store.append(entry)
        store.append(ActionLogEntry(tag = "SYNC", message = "ok"))
        store.awaitPendingWrites()

        val reloaded = ActionLogStore(file).entries.value
        assertEquals(2, reloaded.size)
        assertEquals(entry, reloaded[0])
        assertEquals("SYNC", reloaded[1].tag)
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
    fun clear_removesMemoryAndDiskState() {
        val file = logFile()
        val store = ActionLogStore(file)
        store.append(ActionLogEntry(tag = "T", message = "secret"))
        store.awaitPendingWrites()
        assertTrue(file.exists())

        store.clear()
        store.awaitPendingWrites()
        assertTrue(store.entries.value.isEmpty())
        assertFalse(file.exists())
        assertTrue(ActionLogStore(file).entries.value.isEmpty())
    }

    @Test
    fun appendSync_crashEntryVisibleAfterRestart() {
        val file = logFile()
        val crash = CrashLogger.formatCrash("main", IllegalStateException("boom"))
        ActionLogStore.appendSync(file, ActionLogEntry(tag = "CRASH", message = crash, isError = true))

        val reloaded = ActionLogStore(file).entries.value
        assertEquals(1, reloaded.size)
        assertEquals("CRASH", reloaded[0].tag)
        assertTrue(reloaded[0].isError)
        assertTrue(reloaded[0].message.contains("IllegalStateException"))
        assertTrue(reloaded[0].message.contains("boom"))
    }

    @Test
    fun decodeLine_ignoresCorruptLines() {
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
