# Physical Android Device APK Binary

This directory contains the ready-to-install Android package (`.apk`) for physical device deployment and thesis experimental evaluation.

## Binary Specifications

| Attribute | Specification |
| :--- | :--- |
| **File Name** | `thesis-app-release.apk` |
| **Package Name** | `com.example.thesis` |
| **Min SDK / Target SDK** | `26` (Android 8.0) / `35` (Android 15) |
| **XMTP Environment** | `PRODUCTION` (MLS RFC 9420 + gRPC TLS) |
| **Build Profile** | `release` (R8 Minified, Resource Shrunk, Non-Debuggable) |
| **Size** | `28.8 MB` (`28,787,073 bytes`) |
| **SHA-256 Checksum** | `36f490e6bea6e3a178138df6c4eee93e7945e25584c7b6419e6c74eb37ed647b` |
| **Signer Verification** | `APK Signature Scheme v2 (Verified)` |
| **Supported ABIs** | `arm64-v8a` (Physical 64-bit ARM Phones) |

## Cryptographic & Network Self-Verification

Open **Action & Telemetry Log** inside the app and tap **Verify Crypto & Network (7 Checks)** to run live empirical checks on:
1. Hardware Keystore KEK isolation (`encoded == null`, StrongBox / TEE hardware level)
2. DEK unwrap & AES-256-GCM AEAD tamper rejection (`AEADBadTagException`)
3. Ethereum EIP-191 secp256k1 signature + independent `ecrecover` & BIP-62 low-S proof
4. XMTP MLS installation Ed25519 signature & tamper verification
5. On-disk SQLCipher ciphertext verification (confirms plaintext `"SQLite format 3"` header is absent)
6. Live XMTP `PRODUCTION` network registration & ledger check (`inboxState(refreshFromNetwork = true)`, `canMessage`, `inboxIdFromIdentity`)
7. Release binary & production network posture

Tap **.md** or **.pdf** in the top bar (or **Export PDF** / **Export .md** on the audit card) to print or share the full verification and benchmark report.

## Embedded Native Libraries

- **`libsqlcipher.so`**: SQLCipher 4.6.1 cryptographic database engine (PBKDF2 HMAC-SHA512).
- **`libuniffi_xmtpv3.so`**: XMTP v4 Rust MLS (Messaging Layer Security) core.
- **`libjnidispatch.so`**: Java Native Access (JNA) Android AAR bridge.

## Installation Instructions

### Option 1: Via In-App Updater

If running an older build (`thesis-app-release.apk`-enabled releases), open **Ταυτότητα** -> **Έλεγχος Ενημερώσεων** to download and install automatically.

### Option 2: Direct Device Sideloading

1. Download `thesis-app-release.apk` directly to your Android device.
2. Tap `thesis-app-release.apk` and confirm installation.
3. Launch **THESIS** from the app launcher.
