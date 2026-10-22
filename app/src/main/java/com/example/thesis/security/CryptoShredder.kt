package com.example.thesis.security

import android.content.Context
import android.util.Log
import com.example.thesis.database.DatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Implements GDPR Article 17 (Right to Erasure) via cryptographic shredding.
 *
 * ## Architecture & Guarantees
 * 1. Step 1 (Point of No Return): The Key Encryption Key (KEK) is destroyed from Android
 *    KeyStore via [KeystoreManager.deleteKeystoreEntry]. The DEK envelope becomes permanently
 *    undecipherable, rendering all past database payloads mathematically unrecoverable.
 * 2. Step 2: The account-isolated database file path is resolved via
 *    [DatabaseFactory.resolveDatabaseFile].
 * 3. Step 3 (Storage Hygiene under NonCancellable): Discovers and unlinks all database artifacts:
 *    the primary `.db` file, auxiliary journaling files (`-wal`, `-shm`, `-journal`, `-mj*`),
 *    and the isolated account storage directory (`xmtp_<hash>/`).
 *
 * ## Invariant
 * If file deletion fails or encounters an I/O error, the Keystore key remains deleted.
 * Cryptographic erasure is absolute: data is irrecoverable even if orphaned ciphertext lingers.
 *
 * ## Zero-PII Logging
 * No Ethereum addresses, transaction hashes, or full disk paths are emitted to Logcat.
 */
class CryptoShredder(
    context: Context,
    private val keystoreManager: KeystoreManager = KeystoreManager(context),
    private val databaseFactory: DatabaseFactory = DatabaseFactory(context)
) {
    companion object {
        private const val TAG: String = "CryptoShredder"
        private val AUXILIARY_FILE_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")
    }

    /**
     * Executes atomic crypto-shredding and storage erasure for the given Ethereum account.
     *
     * @param ethAddress Raw or mixed-case hex Ethereum address representing the account sandbox.
     * @return [ShredResult.Success] on complete deletion, [ShredResult.PartialSuccess] if KEK
     *         was purged but some files failed physical removal, or a typed [ShredResult.Failure].
     */
    suspend fun shredAccountData(ethAddress: String): ShredResult = withContext(Dispatchers.IO) {
        val sanitisedAddress = DatabaseFactory.sanitiseEthereumAddress(ethAddress)
            ?: return@withContext ShredResult.Failure.InvalidAddress(
                message = "Address format validation failed. No erasure sequence initiated."
            )

        Log.i(TAG, "shredAccountData: starting cryptographic erasure sequence [address=REDACTED]")

        // ── Step 1: Destroy Keystore KEK (Point of No Return) ────────────────
        val keystoreOutcome = keystoreManager.deleteKeystoreEntry()
        if (keystoreOutcome is KeystoreResult.Failure) {
            Log.e(TAG, "shredAccountData: Keystore KEK destruction failed. Aborting erasure.")
            return@withContext ShredResult.Failure.KeystoreDeletionFailed(
                message = "Failed to destroy hardware Keystore key. Account data remains protected.",
                cause = keystoreOutcome.cause
            )
        }

        Log.i(TAG, "shredAccountData: Keystore KEK successfully purged [point-of-no-return reached]")

        // ── Step 2 & 3: Resolve and physically delete files (NonCancellable) ──
        withContext(NonCancellable) {
            val nonFatalErrors = mutableListOf<String>()

            try {
                val dbFile = databaseFactory.resolveDatabaseFile(sanitisedAddress)
                val filesToDelete = collectAllDatabaseArtifacts(dbFile)

                filesToDelete.forEach { file ->
                    val outcome = deleteFileSafely(file = file)
                    if (outcome is FileDeleteOutcome.Failed) {
                        nonFatalErrors.add("File[${outcome.suffix}]: ${outcome.exceptionClass}")
                    }
                }

                // Clean up isolated account database directory if present
                val accountDir = databaseFactory.resolveAccountDatabaseDirectory(sanitisedAddress)
                if (accountDir.exists()) {
                    val dirDeleted = accountDir.deleteRecursively()
                    if (!dirDeleted) {
                        Log.w(TAG, "shredAccountData: account directory deleteRecursively returned false")
                        nonFatalErrors.add("DirectoryCleanup: deleteRecursivelyFailed")
                    }
                }
            } catch (e: Exception) {
                // Redact exception details to avoid leaking canonical file paths with address hashes
                Log.w(TAG, "shredAccountData: Exception during storage unlinking: ${e::class.java.simpleName}")
                nonFatalErrors.add("StorageResolution: ${e::class.java.simpleName}")
            }

            if (nonFatalErrors.isEmpty()) {
                Log.i(TAG, "shredAccountData: All storage artifacts expunged successfully.")
                ShredResult.Success
            } else {
                Log.w(
                    TAG,
                    "shredAccountData: Cryptographic shred succeeded; storage cleanup had ${nonFatalErrors.size} non-fatal issue(s)."
                )
                ShredResult.PartialSuccess(
                    message = "Hardware key destroyed (data permanently inaccessible). Storage cleanup encountered non-fatal errors.",
                    nonFatalErrors = nonFatalErrors
                )
            }
        }
    }

    /**
     * Discovers all database artifacts sharing the database file prefix, including WAL,
     * SHM, rollback journals, and multi-journal rollback files.
     */
    private fun collectAllDatabaseArtifacts(dbFile: File): List<File> {
        val discoveredFiles = mutableSetOf<File>()
        discoveredFiles.add(dbFile)

        // Known static suffixes
        AUXILIARY_FILE_SUFFIXES.forEach { suffix ->
            discoveredFiles.add(File("${dbFile.path}$suffix"))
        }

        // Dynamic prefix search to catch transient multi-journal (-mj*) or temp files
        val parentDir = dbFile.parentFile
        if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
            val matchingFiles = parentDir.listFiles { _, name ->
                name.startsWith(dbFile.name)
            }
            matchingFiles?.let { discoveredFiles.addAll(it) }
        }

        return discoveredFiles.toList()
    }

    /**
     * Safely unlinks a file, attempting size zeroization prior to deletion where permitted.
     * Logs only the file extension / suffix — never the base filename or path.
     */
    private fun deleteFileSafely(file: File): FileDeleteOutcome {
        val suffix = file.name.substringAfter(DatabaseFactory.DB_NAME_PREFIX, missingDelimiterValue = file.extension)
            .substringAfter(".", missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else ".$it" }

        if (!file.exists()) {
            return FileDeleteOutcome.Skipped(suffix)
        }

        return try {
            // Attempt truncation to destroy inode payload references on disk before unlinking
            try {
                if (file.canWrite() && file.isFile && file.length() > 0) {
                    RandomAccessFile(file, "rws").use { raf ->
                        raf.setLength(0)
                    }
                }
            } catch (_: Throwable) {
                // Non-fatal: proceed to physical unlink
            }

            val deleted = file.delete()
            if (deleted) {
                Log.d(TAG, "deleteFileSafely: unlinked artifact [suffix=$suffix]")
                FileDeleteOutcome.Deleted(suffix)
            } else {
                Log.w(TAG, "deleteFileSafely: file.delete() returned false [suffix=$suffix]")
                FileDeleteOutcome.Failed(
                    suffix = suffix,
                    exceptionClass = "FileNotDeletedException",
                    cause = RuntimeException("delete() returned false for suffix=$suffix")
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "deleteFileSafely: SecurityException [suffix=$suffix]")
            FileDeleteOutcome.Failed(
                suffix = suffix,
                exceptionClass = e::class.java.simpleName,
                cause = e
            )
        }
    }
}

private sealed interface FileDeleteOutcome {
    val suffix: String
    data class Deleted(override val suffix: String) : FileDeleteOutcome
    data class Skipped(override val suffix: String) : FileDeleteOutcome
    data class Failed(
        override val suffix: String,
        val exceptionClass: String,
        val cause: Throwable
    ) : FileDeleteOutcome
}

sealed interface ShredResult {
    data object Success : ShredResult

    data class PartialSuccess(
        val message: String,
        val nonFatalErrors: List<String>
    ) : ShredResult

    sealed interface Failure : ShredResult {
        data class InvalidAddress(val message: String) : Failure

        data class KeystoreDeletionFailed(
            val message: String,
            val cause: Throwable
        ) : Failure
    }
}
