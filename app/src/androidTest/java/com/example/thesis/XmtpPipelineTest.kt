package com.example.thesis

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.thesis.database.DatabaseFactory
import com.example.thesis.identity.ThesisSigner
import com.example.thesis.security.KeystoreManager
import com.example.thesis.xmtp.XmtpEngine
import com.example.thesis.xmtp.XmtpEngineState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmtp.android.library.XMTPEnvironment

@RunWith(AndroidJUnit4::class)
class XmtpPipelineTest {

    @Test
    fun testCompletePipelineInitialization(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val keystoreManager = KeystoreManager(context)
        val databaseFactory = DatabaseFactory(context)
        val signer = ThesisSigner.createEphemeral()

        // 1. Hardware DEK resolution
        val dek = keystoreManager.getOrCreateDatabaseKey().getOrThrow()
        assertTrue(dek.size == 32)

        // 2. Database sandboxing directory
        val dbFile = databaseFactory.resolveDatabaseFile(signer.address)
        val dbDir = dbFile.parentFile ?: context.filesDir

        // 3. XMTP Engine initialization
        val engine = XmtpEngine(context, XMTPEnvironment.DEV)
        engine.initialise(
            signer = signer,
            dbDirectory = dbDir,
            dbEncryptionKey = dek.copyOf()
        )

        // Clean DEK copy in memory
        dek.fill(0x00)

        // 4. Await Ready state
        withTimeout(30_000) {
            val finalState = engine.state.first { it is XmtpEngineState.Ready || it is XmtpEngineState.Error }
            assertTrue("Expected Ready state but got $finalState", finalState is XmtpEngineState.Ready)
            val client = (finalState as XmtpEngineState.Ready).client
            assertNotNull(client.inboxId)
        }

        // 5. Teardown
        engine.shutdown()
        signer.close()
    }
}
