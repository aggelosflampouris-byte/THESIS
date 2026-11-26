package com.example.thesis.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pruebas de regresion para asegurar que proguard-rules.pro conserva las clases criticas bajo R8 Full Mode.
 */
class ReleaseProguardAuditTest {

    // Localizamos proguard-rules.pro independientemente de si Gradle ejecuta desde la raiz o desde :app.
    private fun readProguardRules(): String {
        val candidates = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("No se encontro proguard-rules.pro en ${File(".").absolutePath}")
        return file.readText()
    }

    @Test
    fun test_roomAndWorkManagerConstructorsAndWorkersPreserved() {
        val rules = readProguardRules()

        assertTrue(
            "Debe conservarse el constructor publico <init>() de RoomDatabase bajo R8 Full Mode",
            rules.contains("-keep class * extends androidx.room.RoomDatabase") &&
                rules.contains("public <init>();")
        )
        assertTrue(
            "Debe conservarse WorkDatabase_Impl para WorkManagerInitializer",
            rules.contains("-keep class androidx.work.impl.WorkDatabase_Impl { *; }")
        )
        assertTrue(
            "Debe conservarse CoroutineWorker y su metodo doWork",
            rules.contains("-keep class * extends androidx.work.CoroutineWorker") &&
                rules.contains("public java.lang.Object doWork(kotlin.coroutines.Continuation);")
        )
        assertTrue(
            "Debe conservarse startWork en CoroutineWorker",
            rules.contains("-keepclassmembers class androidx.work.CoroutineWorker") &&
                rules.contains("public *** startWork(...);")
        )
    }

    @Test
    fun test_xmtpUniffiJnaAndProtobufRulesPreserved() {
        val rules = readProguardRules()

        assertTrue("Falta regla uniffi.**", rules.contains("-keep class uniffi.** { *; }"))
        assertTrue("Falta regla com.sun.jna.**", rules.contains("-keep class com.sun.jna.** { *; }"))
        assertTrue(
            "Falta regla para implementaciones de com.sun.jna.Callback",
            rules.contains("-keepclassmembers class * implements com.sun.jna.Callback")
        )
        assertTrue(
            "Falta regla para org.xmtp.android.library.**",
            rules.contains("-keep class org.xmtp.android.library.** { *; }")
        )
        assertTrue(
            "Falta regla para GeneratedMessageLite y sus subclases",
            rules.contains("-keep class com.google.protobuf.GeneratedMessageLite { *; }") &&
                rules.contains("-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }")
        )
        assertTrue(
            "Falta regla para DefaultEntryHolder de XMTP Protobuf",
            rules.contains("-keep class org.xmtp.proto.**$*DefaultEntryHolder { *; }")
        )
    }

    @Test
    fun test_cryptoWebrtcAndAttributesPreserved() {
        val rules = readProguardRules()

        assertTrue("Falta regla sqlcipher", rules.contains("-keep class net.zetetic.database.sqlcipher.** { *; }"))
        assertTrue("Falta regla webrtc", rules.contains("-keep class org.webrtc.** { *; }"))
        assertTrue("Falta regla bouncycastle", rules.contains("-keep class org.bouncycastle.** { *; }"))
        assertTrue("Falta regla tink", rules.contains("-keep class com.google.crypto.tink.** { *; }"))
        assertTrue("Falta regla web3j", rules.contains("-keep class org.web3j.crypto.** { *; }"))

        val attributesLine = rules.lineSequence()
            .filter { it.trimStart().startsWith("-keepattributes") }
            .joinToString(" ")
        listOf("Signature", "InnerClasses", "EnclosingMethod", "*Annotation*", "SourceFile", "LineNumberTable")
            .forEach { attr ->
                assertTrue("Falta atributo R8 $attr en -keepattributes", attributesLine.contains(attr))
            }
    }
}

