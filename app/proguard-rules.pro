# Optimization and obfuscation rules for THESIS project.

# ─── SQLCipher Native JNI Rules ──────────────────────────────────────────────
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }
-dontwarn net.sqlcipher.**
-dontwarn net.zetetic.**

# ─── XMTP v4 / UniFFI / JNA Native Bridge Rules ─────────────────────────────
-keep class uniffi.** { *; }
-keep interface uniffi.** { *; }
-keep class org.xmtp.android.library.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keepclassmembers class * implements com.sun.jna.Callback {
    public *;
}
-dontwarn com.sun.jna.**
-dontwarn uniffi.**

# ─── Room y WorkManager (evita que R8 Full Mode elimine constructores y doWork) ──
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
    public java.lang.Object doWork(kotlin.coroutines.Continuation);
}
-keepclassmembers class androidx.work.CoroutineWorker {
    public *** startWork(...);
    public java.lang.Object doWork(kotlin.coroutines.Continuation);
}

# ─── BouncyCastle Cryptographic Provider Rules ──────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ─── Protobuf / gRPC Rules ──────────────────────────────────────────────────
-keep class com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class org.xmtp.proto.**$*DefaultEntryHolder { *; }
-dontwarn io.grpc.**
-dontwarn io.grpc.netty.**
-dontwarn io.netty.**

# ─── Web3j & Google Tink Cryptographic Primitives ───────────────────────────
-keep class org.web3j.crypto.** { *; }
-dontwarn org.web3j.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ─── Suppress Harmless Metadata Warnings ────────────────────────────────────
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.bouncycastle.asn1.cms.**
-dontwarn org.slf4j.impl.**

# ─── Strip Verbose/Debug/Info Logcat Calls in Release Builds (Zero-PII) ──────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ─── WebRTC JNI Rules (evita ofuscacion de callbacks nativos de libjingle) ────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ─── Conservamos firma generica, clases internas, anotaciones y lineas para R8 y CrashLogger ──
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
