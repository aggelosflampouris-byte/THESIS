# Optimization and obfuscation rules for THESIS project.

# SQLCipher Native JNI bindings
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }

# UniFFI Rust / XMTP SDK v4 JNI interop
-keep class uniffi.** { *; }
-keep class org.xmtp.android.library.** { *; }
-dontwarn uniffi.**

# JNA Native dispatch
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Web3j Cryptographic primitives
-keep class org.web3j.crypto.** { *; }
-dontwarn org.web3j.**

# Google Tink Hardware / AEAD Crypto
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Netty / gRPC transports (desktop fallbacks)
-dontwarn io.grpc.netty.**
-dontwarn io.netty.**

# Suppress harmless compile-time metadata warnings from Guava / Web3j / XMTP
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.bouncycastle.asn1.cms.**
-dontwarn org.slf4j.impl.**
