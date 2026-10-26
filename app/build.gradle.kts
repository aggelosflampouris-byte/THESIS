plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.thesis"
    // compileSdk set to 37: Required by modern AndroidX artifacts (core-ktx:1.19.1, lifecycle-runtime:2.11.0, activity:1.13.0)
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.thesis"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ====================================================================
        // ABI Filter Integrity:
        // Restricts JNI architectures strictly to modern 64-bit platforms:
        // - arm64-v8a: Physical modern Android hardware (mandated by Google Play).
        // - x86_64: Official Android Studio emulator virtual devices.
        // Strips legacy 32-bit binaries (armeabi, armeabi-v7a, x86, mips, mips64):
        // 1. Cuts ~40MB of redundant uncompressed .so bloat from XMTP (30.3MB) and SQLCipher (9.4MB).
        // 2. Eliminates mixed 32/64-bit UnsatisfiedLinkError crashes when loading native libraries.
        // ====================================================================
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // ================================================================
            // JNI Native Library Duplicate Resolution:
            // - libjnidispatch.so: Packaged by net.java.dev.jna:jna (AAR) and
            //   transitively in desktop/JVM JNA jars. pickFirst guarantees
            //   deterministic selection of the AAR JNI binary.
            // - libc++_shared.so: Packaged when multiple native modules (Rust
            //   UniFFI for libxmtp and SQLCipher C++ runtimes) link against the
            //   C++ standard library. pickFirst resolves symbol collisions.
            // ================================================================
            pickFirsts += setOf(
                "**/libjnidispatch.so",
                "**/libc++_shared.so"
            )

            // ================================================================
            // JNI Native Library Exclusions:
            // Exclude desktop native transports and non-Android shared objects:
            // - libnetty*.so: Unused desktop Linux transports (e.g. epoll/kqueue);
            //   Android uses io.grpc:grpc-okhttp.
            // - *.jnilib, *.dll, *.dylib: Desktop macOS and Windows native binaries.
            // ================================================================
            excludes += setOf(
                "**/libnetty*.so",
                "**/*.jnilib",
                "**/*.dll",
                "**/*.dylib"
            )
        }

        resources {
            // ================================================================
            // Java Resource & Metadata Exclusions:
            // Prevents AGP packaging collisions from transitive dependencies:
            // - META-INF/native/**, META-INF/native-image/**: Desktop Netty/GraalVM
            //   native metadata and transport libraries embedded in JAR resources.
            // - AL2.0, LGPL2.1, LICENSE*, NOTICE*: Redundant license/notice manifests.
            // - io.netty.versions.properties, DEPENDENCIES, INDEX.LIST: Build descriptors.
            // - **/*.kotlin_module: Duplicate Kotlin module metadata files.
            // ================================================================
            excludes += setOf(
                "META-INF/native/**",
                "META-INF/native-image/**",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "**/*.kotlin_module"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // ------------------------------------------------------------------------
    // Jetpack Compose & Core AndroidX UI
    // ------------------------------------------------------------------------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ------------------------------------------------------------------------
    // XMTP Android SDK v4 (libxmtp Rust MLS core via UniFFI)
    // Transitive dependencies:
    // - com.google.crypto.tink:tink-android:1.8.0 (aligned with security-crypto)
    // - net.java.dev.jna:jna:5.14.0 (AAR JNI dispatch)
    // - io.grpc:grpc-okhttp:1.62.2 (mobile gRPC transport)
    // ------------------------------------------------------------------------
    implementation("org.xmtp:android:4.0.3")

    // ------------------------------------------------------------------------
    // Web3j Cryptography: EIP-191 & SECP256K1 Key Derivation
    // Exclude legacy bcprov-jdk15on to prevent duplicate classes with bcprov-jdk18on
    // ------------------------------------------------------------------------
    implementation("org.web3j:crypto:4.9.8")

    // ------------------------------------------------------------------------
    // Bouncy Castle: SECP256K1, ECDSA, KeccakDigest for ThesisSigner
    // ------------------------------------------------------------------------
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ------------------------------------------------------------------------
    // AndroidX Security Crypto: Hardware Keystore Envelope Encryption
    // Aligns transitively with tink-android:1.8.0 without version drift.
    // ------------------------------------------------------------------------
    implementation("androidx.security:security-crypto-ktx:1.1.0-alpha06")

    // ------------------------------------------------------------------------
    // SQLCipher & SQLite Database Layer
    // Declared without "@aar" artifact truncation to enable Gradle POM resolution
    // to reconcile transitive androidx.sqlite:sqlite (2.2.0 -> 2.4.0) with
    // androidx.sqlite:sqlite-ktx:2.4.0.
    // ------------------------------------------------------------------------
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // ------------------------------------------------------------------------
    // Local Unit Testing (Isolated to JVM Host, Never Packaged into APK)
    // ------------------------------------------------------------------------
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("app.cash.turbine:turbine:1.2.0")

    // ------------------------------------------------------------------------
    // Android Instrumented Testing (Isolated to Test APK)
    // ------------------------------------------------------------------------
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("app.cash.turbine:turbine:1.2.0")

    // ------------------------------------------------------------------------
    // Debug Tooling (Stripped from Release Builds)
    // ------------------------------------------------------------------------
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
}
