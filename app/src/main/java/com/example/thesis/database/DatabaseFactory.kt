package com.example.thesis.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.DefaultDatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import java.io.File
import com.example.thesis.util.CryptoUtils

/**
 * Production-grade Database Factory for Account-Isolated, SQLCipher Encrypted Databases.
 *
 * Architecture & Security Guarantees:
 * - Account Sandboxing: Databases are deterministically partitioned by deriving filenames
 *   from the lowercase Ethereum address: `xmtp_db_<SHA256(address)>.db`.
 * - Input Sanitization: Strict regex whitelisting (`^0x[0-9a-fA-F]{40}$`) prevents path traversal
 *   and directory manipulation attacks before filesystem resolution.
 * - Modern SQLCipher 4.6.1 Integration: Bypasses obsolete `loadLibs()` mechanisms and utilizes
 *   native static initialization and [SQLiteDatabase.openOrCreateDatabase].
 * - In-Memory Key Zeroization: Plaintext DEK buffers are guaranteed to be wiped (`fill(0x00)`)
 *   inside a `finally` block immediately upon handoff to native storage.
 * - Resource Discipline: All filesystem operations and native SQLite statement executions are
 *   dispatched on [Dispatchers.IO], with statement and cursor resources consumed strictly via [use].
 */
class DatabaseFactory(context: Context) {

    private val appContext: Context = context.applicationContext

    companion object {
        const val DB_EXTENSION: String = ".db"
        const val DB_NAME_PREFIX: String = "xmtp_db_"

        /**
         * SQLCipher PRAGMA Configuration:
         * - 4096-byte page size aligned with Android flash memory block sizes.
         * - PBKDF2-HMAC-SHA512 with 256,000 iterations for robust key derivation.
         * - HMAC-SHA512 page integrity checking.
         */
        private const val PRAGMA_CIPHER_PAGE_SIZE: String = "PRAGMA cipher_page_size = 4096;"
        private const val PRAGMA_KDF_ITER: String = "PRAGMA kdf_iter = 256000;"
        private const val PRAGMA_CIPHER_HMAC_ALGO: String = "PRAGMA cipher_hmac_algorithm = HMAC_SHA512;"
        private const val PRAGMA_CIPHER_KDF_ALGO: String = "PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA512;"

        val ETHEREUM_ADDRESS_REGEX: Regex = Regex("^0[xX][0-9a-fA-F]{40}$")

        fun sanitiseEthereumAddress(address: String): String? {
            val trimmed = address.trim()
            return if (ETHEREUM_ADDRESS_REGEX.matches(trimmed)) {
                trimmed.lowercase()
            } else {
                null
            }
        }

        fun sanitizeEthereumAddress(address: String): String? =
            sanitiseEthereumAddress(address)
    }

    private val defaultErrorHandler: DatabaseErrorHandler = DefaultDatabaseErrorHandler()

    /**
     * Opens or creates an account-isolated, encrypted SQLCipher database for the given Ethereum address.
     *
     * In-Memory Key Hygiene:
     * The input [dekBytes] is unconditionally zeroized in memory within a `finally` block
     * immediately after handoff to native storage, regardless of success or failure.
     *
     * @param dekBytes 32-byte Data Encryption Key from KeyStore. Wiped before function returns.
     * @param ethAddress Hex-encoded Ethereum address representing the account sandbox boundary.
     * @param hook Optional custom [SQLiteDatabaseHook] for connection-pool pragma execution.
     * @param errorHandler Optional custom [DatabaseErrorHandler] for database corruption recovery.
     * @return [DatabaseResult.Success] containing the opened [SQLiteDatabase], or typed [DatabaseResult.Failure].
     */
    suspend fun openEncryptedDatabase(
        dekBytes: ByteArray,
        ethAddress: String,
        hook: SQLiteDatabaseHook = CipherHook,
        errorHandler: DatabaseErrorHandler = defaultErrorHandler
    ): DatabaseResult<SQLiteDatabase> = withContext(Dispatchers.IO) {
        var db: SQLiteDatabase? = null
        var initializationSucceeded = false
        try {
            val sanitisedAddress = sanitiseEthereumAddress(ethAddress)
                ?: return@withContext DatabaseResult.Failure.InvalidAddress(
                    message = "Address format validation failed. Expected '0x' followed by 40 hex characters."
                )

            val dbFile = resolveDatabaseFile(sanitisedAddress)
            ensureParentDirectoryExists(dbFile)

            db = openSQLCipherDatabase(
                dbFile = dbFile,
                dekBytes = dekBytes,
                hook = hook,
                errorHandler = errorHandler
            )
            applyHardenedCipherPragmas(db)
            verifyDatabaseIntegrity(db)

            // Enable WAL mode for multi-threaded read concurrency
            db.enableWriteAheadLogging()

            initializationSucceeded = true
            DatabaseResult.Success(db)
        } catch (e: CancellationException) {
            // Rethrow cancellation to maintain coroutine structured concurrency contracts.
            // The finally block guarantees immediate closure of native DB pointers and DEK scrubbing.
            throw e
        } catch (e: SQLiteDatabaseOpenException) {
            DatabaseResult.Failure.OpenFailed(
                message = "SQLCipher native engine failed to open or authenticate database: ${e.message}",
                cause = e
            )
        } catch (e: IntegrityCheckFailedException) {
            DatabaseResult.Failure.IntegrityCheckFailed(
                message = e.message ?: "Database integrity check detected corruption or invalid key.",
                cause = e
            )
        } catch (e: Exception) {
            DatabaseResult.Failure.Unknown(
                message = "Unexpected failure during database provisioning: ${e.message}",
                cause = e
            )
        } finally {
            // Close native SQLite connection and statement handles if setup aborted or was cancelled
            if (!initializationSucceeded && db != null) {
                try {
                    if (db.isOpen) db.close()
                } catch (_: Throwable) {
                    // Suppress secondary closure exception to preserve root failure cause
                }
            }
            // Unconditional in-memory key zeroization
            dekBytes.fill(0x00)
        }
    }

    /**
     * Safely closes an open [SQLiteDatabase] on [Dispatchers.IO].
     */
    suspend fun closeDatabase(db: SQLiteDatabase): Unit = withContext(Dispatchers.IO) {
        if (db.isOpen) {
            db.close()
        }
    }

    /**
     * Checks if a database file exists for the given Ethereum address without opening it.
     */
    suspend fun databaseExists(ethAddress: String): Boolean = withContext(Dispatchers.IO) {
        val sanitised = sanitiseEthereumAddress(ethAddress) ?: return@withContext false
        resolveDatabaseFile(sanitised).exists()
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Sanitization & Path Derivation
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Validates raw input against [ETHEREUM_ADDRESS_REGEX] to eliminate path-traversal attacks.
     * Returns canonical lowercase address on success, or null if validation fails.
     */
    internal fun sanitiseEthereumAddress(address: String): String? =
        Companion.sanitiseEthereumAddress(address)

    internal fun sanitizeEthereumAddress(address: String): String? =
        Companion.sanitiseEthereumAddress(address)

    /**
     * Deterministically maps the sanitized lowercase address to an isolated database [File]:
     * `xmtp_db_<SHA256(lowercaseAddress)>.db`.
     *
     * Enforces canonical path verification to ensure resolution remains strictly within
     * the application's databases sandbox directory.
     */
    fun resolveDatabaseFile(sanitisedAddress: String): File {
        val hash = sha256Hex(sanitisedAddress)
        val filename = "$DB_NAME_PREFIX$hash$DB_EXTENSION"
        val dbFile = appContext.getDatabasePath(filename)

        val parentDir = dbFile.parentFile ?: throw SecurityException("Database file has no parent directory")
        val expectedBase = appContext.getDatabasePath("anchor").parentFile?.canonicalFile
            ?: throw SecurityException("Cannot resolve application databases directory")

        if (!parentDir.canonicalFile.startsWith(expectedBase)) {
            throw SecurityException("Path traversal attempt detected: ${dbFile.canonicalPath}")
        }

        return dbFile
    }

    /**
     * Resolves an isolated database directory for XMTP v4 (libxmtp) storage.
     */
    fun resolveAccountDatabaseDirectory(sanitisedAddress: String): File {
        val hash = sha256Hex(sanitisedAddress)
        val dirName = "xmtp_$hash"
        val expectedBase = appContext.getDatabasePath("anchor").parentFile?.canonicalFile
            ?: throw SecurityException("Cannot resolve application databases directory")
        val dbDir = File(expectedBase, dirName)

        if (!dbDir.canonicalFile.startsWith(expectedBase)) {
            throw SecurityException("Path traversal attempt detected: ${dbDir.canonicalPath}")
        }

        if (!dbDir.exists()) {
            check(dbDir.mkdirs()) {
                "Failed to create account database directory: ${dbDir.absolutePath}"
            }
        }
        return dbDir
    }

    // ────────────────────────────────────────────────────────────────────────
    //  SQLCipher Native Interop
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Invokes SQLCipher 4.6.1 [SQLiteDatabase.openOrCreateDatabase].
     * Compatible with modern native auto-loading (no obsolete `loadLibs()` invocation).
     *
     * Immediate in-memory zeroization of [dekBytes] occurs in the `finally` block
     * immediately upon completion of the native JNI invocation.
     */
    private fun openSQLCipherDatabase(
        dbFile: File,
        dekBytes: ByteArray,
        hook: SQLiteDatabaseHook,
        errorHandler: DatabaseErrorHandler
    ): SQLiteDatabase {
        return try {
            SQLiteDatabase.openOrCreateDatabase(
                /* file          = */ dbFile,
                /* password      = */ dekBytes,
                /* cursorFactory = */ null,
                /* errorHandler  = */ errorHandler,
                /* hook          = */ hook
            )
        } catch (e: Exception) {
            throw SQLiteDatabaseOpenException("Failed to open SQLCipher database at ${dbFile.path}", e)
        } finally {
            dekBytes.fill(0x00)
        }
    }

    /**
     * Applies hardened cipher pragmas post-authentication.
     */
    private fun applyHardenedCipherPragmas(db: SQLiteDatabase) {
        db.execSQL(PRAGMA_CIPHER_PAGE_SIZE)
        db.execSQL(PRAGMA_KDF_ITER)
        db.execSQL(PRAGMA_CIPHER_HMAC_ALGO)
        db.execSQL(PRAGMA_CIPHER_KDF_ALGO)
    }

    /**
     * Executes `PRAGMA integrity_check` consuming cursors via [use] to guarantee
     * statement lifecycle disposal and avoid JNI cursor handle leaks.
     */
    private fun verifyDatabaseIntegrity(db: SQLiteDatabase) {
        val errors = mutableListOf<String>()

        db.rawQuery("PRAGMA integrity_check;", null).use { cursor ->
            while (cursor.moveToNext()) {
                val result = cursor.getString(0)
                if (!result.equals("ok", ignoreCase = true)) {
                    errors += result
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw IntegrityCheckFailedException(
                "integrity_check failed with ${errors.size} error(s): ${errors.joinToString("; ")}"
            )
        }
    }

    private fun ensureParentDirectoryExists(file: File) {
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                check(parent.mkdirs()) {
                    "Failed to create database parent directory: ${parent.absolutePath}"
                }
            }
        }
    }

    /**
     * Computes the lowercase hex-encoded SHA-256 digest of [input].
     * Delegates to [CryptoUtils.sha256Hex] to avoid duplication.
     */
    private fun sha256Hex(input: String): String = CryptoUtils.sha256Hex(input)

    /**
     * SQLiteDatabaseHook implementation providing connection-pool wide pragma parity.
     * Invoked for every connection in SQLCipher's connection pool, ensuring multi-threaded
     * WAL-mode reader connections receive identical cryptographic parameters.
     */
    private object CipherHook : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) {
            // cipher_page_size must be configured before sqlite3_key initializes page 1
            connection.execute(PRAGMA_CIPHER_PAGE_SIZE, null, null)
        }

        override fun postKey(connection: SQLiteConnection) {
            connection.execute(PRAGMA_KDF_ITER, null, null)
            connection.execute(PRAGMA_CIPHER_HMAC_ALGO, null, null)
            connection.execute(PRAGMA_CIPHER_KDF_ALGO, null, null)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Domain Exceptions
// ────────────────────────────────────────────────────────────────────────────

internal class SQLiteDatabaseOpenException(message: String, cause: Throwable) :
    Exception(message, cause)

internal class IntegrityCheckFailedException(message: String) :
    Exception(message)

// ────────────────────────────────────────────────────────────────────────────
//  Typed Result Monad
// ────────────────────────────────────────────────────────────────────────────

/**
 * Discriminated domain result type for [DatabaseFactory] operations.
 */
sealed class DatabaseResult<out T> {

    data class Success<T>(val value: T) : DatabaseResult<T>()

    sealed class Failure : DatabaseResult<Nothing>() {
        abstract val message: String

        data class InvalidAddress(override val message: String) : Failure()

        data class OpenFailed(
            override val message: String,
            val cause: Throwable
        ) : Failure()

        data class IntegrityCheckFailed(
            override val message: String,
            val cause: Throwable
        ) : Failure()

        data class Unknown(
            override val message: String,
            val cause: Throwable
        ) : Failure()
    }

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure.InvalidAddress -> throw IllegalArgumentException(message)
        is Failure.OpenFailed -> throw cause
        is Failure.IntegrityCheckFailed -> throw cause
        is Failure.Unknown -> throw cause
    }

    fun getOrNull(): T? = (this as? Success)?.value

    inline fun <R> map(transform: (T) -> R): DatabaseResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(action: (T) -> Unit): DatabaseResult<T> {
        if (this is Success) action(value)
        return this
    }

    inline fun onFailure(action: (Failure) -> Unit): DatabaseResult<T> {
        if (this is Failure) action(this)
        return this
    }
}
