# Physical Android Device APK Binary

This directory contains the ready-to-install Android package (`.apk`) for physical device deployment and thesis experimental evaluation.

## Binary Specifications

| Attribute | Specification |
| :--- | :--- |
| **File Name** | `thesis-app-debug.apk` |
| **Package Name** | `com.example.thesis` |
| **Version** | `1.0 (versionCode 1)` |
| **Min SDK / Target SDK** | `26` (Android 8.0) / `34` (Android 14) |
| **Size** | `67 MB` (`67,816,560 bytes`) |
| **SHA-256 Checksum** | `4f08d2a7a389e53ac3acc3999723f652905d17ec548481479c70d96fa9f98802` |
| **Signer Verification** | `APK Signature Scheme v2 (Signed)` |
| **Supported ABIs** | `arm64-v8a` (Physical 64-bit ARM Phones) & `x86_64` (Emulators) |

## Embedded Native Libraries

- **`libsqlcipher.so`**: SQLCipher 4.6.1 cryptographic database engine (PBKDF2 HMAC-SHA512).
- **`libuniffi_xmtpv3.so`**: XMTP v4 Rust MLS (Messaging Layer Security) core.
- **`libjnidispatch.so`**: Java Native Access (JNA) Android AAR bridge.

## Installation Instructions

### Option 1: Via USB Debugging (ADB)

```bash
adb install -r apk/thesis-app-debug.apk
```

### Option 2: Direct Device Sideloading

1. Download or transfer `thesis-app-debug.apk` directly to your Android device storage.
2. Open your device's file manager and tap `thesis-app-debug.apk`.
3. Confirm installation (allow unknown sources if prompted).
4. Launch **THESIS** from the app launcher.
