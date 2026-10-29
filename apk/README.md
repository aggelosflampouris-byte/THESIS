# Physical Android Device APK Binary

This directory contains the ready-to-install Android package (`.apk`) for physical device deployment and thesis experimental evaluation.

## Binary Specifications

| Attribute | Specification |
| :--- | :--- |
| **File Name** | `thesis-app-debug.apk` |
| **Package Name** | `com.example.thesis` |
| **Version** | `1.0.5 (versionCode 2)` |
| **Min SDK / Target SDK** | `26` (Android 8.0) / `34` (Android 14) |
| **Size** | `67 MB` (`69,552,950 bytes`) |
| **SHA-256 Checksum** | `c7b7378752fa15cace205156cd57aa6ea81b0fe6327f555ad2d1065e80fb0353` |
| **Signer Verification** | `APK Signature Scheme v2 (Signed)` |
| **Supported ABIs** | `arm64-v8a` (Physical 64-bit ARM Phones) & `x86_64` (Emulators) |

## What's New in v1.0.5

- **Multi-Device Message Synchronization**: Fixed issue where incoming conversations from peers were omitted by querying both `ALLOWED` and `UNKNOWN` consent states and automatically syncing welcomes from the XMTP network.
- **Dual Identity Peer Resolution**: Added support for connecting via either Ethereum Address (`0x...`) or XMTP Inbox ID (64-character hexadecimal).
- **Identity Details Dialog**: Exposes both Ethereum Wallet Address and XMTP Inbox ID with 1-tap copy buttons and 2-device pairing instructions.
- **Live Stream Reconnection**: Implemented automatic reconnection loop for incoming message streams and instant local UI dispatch.
- **Visual Separation**: Clearly badges Direct Messages (👤), Loopback tests (🔄), and Group chats (👥) to prevent isolated self-chatting.

## Embedded Native Libraries

- **`libsqlcipher.so`**: SQLCipher 4.6.1 cryptographic database engine (PBKDF2 HMAC-SHA512).
- **`libuniffi_xmtpv3.so`**: XMTP v4 Rust MLS (Messaging Layer Security) core.
- **`libjnidispatch.so`**: Java Native Access (JNA) Android AAR bridge.

## Installation Instructions

### Option 1: Via In-App Updater

If running v1.0.4, tap the **🚀 v1.0.5** button in the TopBar or tap **Ταυτότητα** -> **Έλεγχος Ενημερώσεων** to download and install automatically.

### Option 2: Direct Device Sideloading

1. Download `thesis-app-debug.apk` directly to your Android device.
2. Tap `thesis-app-debug.apk` and confirm installation.
3. Launch **THESIS** from the app launcher.
