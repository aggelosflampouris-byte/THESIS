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
-dontwarn com.sun.jna.**
-dontwarn uniffi.**

# ─── BouncyCastle Cryptographic Provider Rules ──────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ─── Protobuf / gRPC Rules ──────────────────────────────────────────────────
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
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
