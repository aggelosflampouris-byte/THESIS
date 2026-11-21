package com.example.thesis.xmtp

import android.content.Context
import android.util.Log
import com.example.thesis.database.DatabaseFactory
import com.example.thesis.identity.UserRejectedSignatureException
import com.example.thesis.security.KeystoreManager
import com.example.thesis.security.KeystoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmtp.android.library.Client
import org.xmtp.android.library.ClientOptions
import org.xmtp.android.library.DelicateApi
import org.xmtp.android.library.SigningKey
import org.xmtp.android.library.XMTPEnvironment
import org.xmtp.android.library.codecs.GroupUpdatedCodec
import org.xmtp.android.library.codecs.ReadReceiptCodec
import uniffi.xmtpv3.InternalException
import java.io.File
import com.example.thesis.util.CryptoUtils
import java.util.Locale
import org.xmtp.android.library.libxmtp.ArchiveElement
import org.xmtp.android.library.libxmtp.ArchiveOptions

/**
 * Engine responsible for the full lifecycle of the XMTP v4 (libxmtp / MLS) client.
 *
 * ## Core Architectural & Concurrency Guarantees
 * 1. **Lifecycle Management**: Exposes an immutable [StateFlow] of [XmtpEngineState]
 *    (`Idle`, `Loading`, `Ready`, `Error`) adhering strictly to Unidirectional Data Flow.
 * 2. **Hardware DB Injection**: Injects the hardware-backed DEK from [KeystoreManager] and
 *    account-isolated storage path from [DatabaseFactory] into [ClientOptions] so all MLS
 *    ratchet trees, messages, and key packages are encrypted locally with SQLCipher.
 * 3. **Concurrency & Idempotency Under Load**: Guarded by an internal synchronization monitor
 *    and a coroutine [Mutex]. Prevents duplicate gRPC calls and concurrent SQLite opens.
 * 4. **Cancellation Integrity**: Preserves Kotlin structured concurrency by never swallowing
 *    [CancellationException]. Handles mid-flight activity rotation without leaking native
 *    Rust pointers or leaving SQLite file locks open.
 * 5. **Native Rust Panic & Error Mapping**: Catches UniFFI [InternalException] (unwind panics)
 *    and maps native errors exhaustively into strongly-typed [XmtpInitError] variants.
 * 6. **Non-Blocking Asynchronous Shutdown**: Dispatches native connection drops and pointer
 *    releases to `Dispatchers.IO` wrapped in [NonCancellable].
 *
 * @param context         Application context; must outlive the engine.
 * @param keystoreManager Hardware envelope encryption manager for DEK derivation.
 * @param databaseFactory Factory resolving account-isolated database directories.
 * @param environment     Target XMTP network environment (e.g. [XMTPEnvironment.PRODUCTION]).
 */
class XmtpEngine(
    context: Context,
    private val keystoreManager: KeystoreManager = KeystoreManager(context.applicationContext),
    private val databaseFactory: DatabaseFactory = DatabaseFactory(context.applicationContext),
    private val environment: XMTPEnvironment = XMTPEnvironment.PRODUCTION,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {

    /**
     * Convenience constructor for initializing with a target [XMTPEnvironment].
     */
    constructor(
        context: Context,
        environment: XMTPEnvironment
    ) : this(
        context = context,
        keystoreManager = KeystoreManager(context.applicationContext),
        databaseFactory = DatabaseFactory(context.applicationContext),
        environment = environment,
        ioDispatcher = Dispatchers.IO
    )

    private val appContext: Context = context.applicationContext

    // ────────────────────────────────────────────────────────────────────────
    //  State Flow (Unidirectional Data Flow)
    // ────────────────────────────────────────────────────────────────────────

    private val lock = Any()
    private val initMutex = Mutex()

    private val _state = MutableStateFlow<XmtpEngineState>(XmtpEngineState.Idle)

    /**
     * Immutable StateFlow window representing the current engine lifecycle state.
     */
    val state: StateFlow<XmtpEngineState> = _state.asStateFlow()

    @Volatile
    private var activeClient: Client? = null

    /**
     * Convenience property returning the active [Client] if in [XmtpEngineState.Ready],
     * or null otherwise.
     */
    val client: Client?
        get() = activeClient

    // ────────────────────────────────────────────────────────────────────────
    //  Dedicated Concurrency Scope
    // ────────────────────────────────────────────────────────────────────────

    private val engineJob = SupervisorJob()
    private val engineScope = CoroutineScope(engineJob + ioDispatcher)

    internal val hasActiveJobs: Boolean
        get() = engineJob.children.any { it.isActive }

    private var activeInitJob: Job? = null

    // ────────────────────────────────────────────────────────────────────────
    //  Public Lifecycle API
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Idempotently starts asynchronous initialisation of the XMTP v4 client.
     *
     * Calling this method while already in [XmtpEngineState.Loading] or [XmtpEngineState.Ready]
     * is a thread-safe no-op.
     *
     * @param signer                 EIP-191 signing key providing decentralized identity.
     * @param customDbDirectory      Optional explicit database directory (defaults to [DatabaseFactory] directory).
     * @param customDbEncryptionKey  Optional explicit 32-byte DEK (defaults to [KeystoreManager] hardware DEK).
     */
    fun initialise(
        signer: SigningKey,
        dbDirectory: File? = null,
        dbEncryptionKey: ByteArray? = null
    ) {
        startInitialization(signer, dbDirectory, dbEncryptionKey)
    }

    /**
     * Starts initialization (or attaches to an in-flight initialization) and suspends until
     * [XmtpEngineState] reaches a terminal state ([XmtpEngineState.Ready] or [XmtpEngineState.Error]).
     */
    suspend fun initialiseAndAwait(
        signer: SigningKey,
        dbDirectory: File? = null,
        dbEncryptionKey: ByteArray? = null
    ): XmtpEngineState {
        val jobToJoin = startInitialization(signer, dbDirectory, dbEncryptionKey)
        jobToJoin?.join()
        return _state.value
    }

    private fun startInitialization(
        signer: SigningKey,
        dbDirectory: File? = null,
        dbEncryptionKey: ByteArray? = null
    ): Job? {
        val ownedKey = dbEncryptionKey?.copyOf()
        synchronized(lock) {
            val current = _state.value
            if (current is XmtpEngineState.Loading || current is XmtpEngineState.Ready) {
                ownedKey?.fill(0x00)
                Log.i(TAG, "initialise() ignored: engine already in state ${current::class.simpleName}")
                return activeInitJob
            }

            _state.value = XmtpEngineState.Loading

            val job = engineScope.launch {
                try {
                    initMutex.withLock {
                        // Double check after acquiring mutex
                        if (_state.value is XmtpEngineState.Ready) {
                            ownedKey?.fill(0x00)
                            return@withLock
                        }

                        val resultState = executeInitialization(signer, dbDirectory, ownedKey)

                        if (coroutineContext.isActive && _state.value is XmtpEngineState.Loading) {
                            if (resultState is XmtpEngineState.Ready) {
                                activeClient = resultState.client
                            }
                            _state.value = resultState
                        } else {
                            // Engine was cancelled or shutdown occurred concurrently during native creation.
                            // Immediately drop the native database connection to prevent leaking JNI pointers or locks.
                            if (resultState is XmtpEngineState.Ready) {
                                withContext(NonCancellable) {
                                    cleanupNativeClient(resultState.client)
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    ownedKey?.fill(0x00)
                    throw e
                } catch (e: Exception) {
                    ownedKey?.fill(0x00)
                    _state.value = mapExceptionToState(e)
                }
            }
            job.invokeOnCompletion { ownedKey?.fill(0x00) }
            activeInitJob = job
            return job
        }
    }

    /**
     * Asynchronously closes the native client and releases Rust pointers without
     * blocking the calling (e.g., Main) thread.
     */
    fun shutdown() {
        synchronized(lock) {
            _state.value = XmtpEngineState.Idle

            val clientToClose = activeClient
            activeClient = null

            activeInitJob?.cancel()
            activeInitJob = null

            engineScope.launch {
                withContext(NonCancellable) {
                    cleanupNativeClient(clientToClose)
                    engineJob.cancelChildren()
                }
            }
        }
    }

    /**
     * Deterministically cancels in-flight initialization, closes the native XMTP SQLite/MLS
     * connection, and awaits full handle release prior to GDPR storage unlinking.
     */
    suspend fun shutdownAndAwait() {
        val clientToClose: Client?
        val jobToCancel: Job?
        synchronized(lock) {
            _state.value = XmtpEngineState.Idle
            clientToClose = activeClient
            activeClient = null
            jobToCancel = activeInitJob
            activeInitJob = null
        }

        jobToCancel?.cancel()
        try {
            jobToCancel?.join()
        } catch (_: CancellationException) {
            // Expected cancellation of in-flight initialization job
        }

        withContext(NonCancellable + ioDispatcher) {
            cleanupNativeClient(clientToClose)
            engineJob.cancelChildren()
        }
    }

    /**
     * Closes the engine and tears down native client connections and background coroutines.
     * Conforms to [AutoCloseable] lifecycle interface.
     */
    override fun close() {
        shutdown()
    }

    // Exportamos mensajes y consentimiento MLS cifrados localmente sin depender de servidores externos.
    suspend fun exportEncryptedArchive(outputFile: File, encryptionKey: ByteArray): Result<Unit> =
        withContext(ioDispatcher) {
            val keyCopy = encryptionKey.copyOf()
            try {
                val currentClient = activeClient
                    ?: return@withContext Result.failure(IllegalStateException("XMTP client is not ready"))
                outputFile.parentFile?.let { parent ->
                    if (!parent.exists()) parent.mkdirs()
                }
                val archiveOptions = ArchiveOptions(
                    archiveElements = listOf(ArchiveElement.MESSAGES, ArchiveElement.CONSENT)
                )
                currentClient.createArchive(outputFile.absolutePath, keyCopy, archiveOptions)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                keyCopy.fill(0x00)
            }
        }

    // Importamos el archivo cifrado MLS en la base SQLCipher local del nuevo dispositivo.
    suspend fun importEncryptedArchive(inputFile: File, encryptionKey: ByteArray): Result<Unit> =
        withContext(ioDispatcher) {
            val keyCopy = encryptionKey.copyOf()
            try {
                val currentClient = activeClient
                    ?: return@withContext Result.failure(IllegalStateException("XMTP client is not ready"))
                require(inputFile.exists() && inputFile.isFile) {
                    "Archive file does not exist: ${inputFile.absolutePath}"
                }
                currentClient.importArchive(inputFile.absolutePath, keyCopy)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                keyCopy.fill(0x00)
            }
        }

    suspend fun revokeOtherInstallations(signer: SigningKey): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                val currentClient = activeClient
                    ?: return@withContext Result.failure(IllegalStateException("XMTP client is not ready"))
                currentClient.revokeAllOtherInstallations(signer)
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    @OptIn(DelicateApi::class)
    private suspend fun cleanupNativeClient(targetClient: Client?) {
        if (targetClient == null) return
        try {
            Log.i(TAG, "Releasing native XMTP client resources and database connection")
            targetClient.dropLocalDatabaseConnection()
        } catch (e: Exception) {
            Log.w(TAG, "Exception during native client cleanup: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Client Construction & Hardware DB Injection
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun executeInitialization(
        signer: SigningKey,
        customDbDirectory: File?,
        customDbEncryptionKey: ByteArray?
    ): XmtpEngineState = withContext(ioDispatcher) {
        var dekToZero: ByteArray? = customDbEncryptionKey
        try {
            val address = signer.publicIdentity.identifier
            val sanitisedAddress = CryptoUtils.sanitiseEthereumAddress(address)
                ?: return@withContext XmtpEngineState.Error(
                    XmtpInitError.IdentityError(
                        message = "Invalid Ethereum address format for identity",
                        cause = IllegalArgumentException("Address does not match 0x[0-9a-fA-F]{40}")
                    )
                )

            val dbDirectory = customDbDirectory ?: databaseFactory.resolveAccountDatabaseDirectory(sanitisedAddress)
            ensureDirectoryAccessible(dbDirectory)

            val dekBytes = when (val keyResolution = resolveDatabaseEncryptionKey(customDbEncryptionKey)) {
                is KeyResolutionResult.Success -> {
                    if (keyResolution.shouldZeroize) dekToZero = keyResolution.key
                    keyResolution.key
                }

                is KeyResolutionResult.Failure -> {
                    return@withContext XmtpEngineState.Error(keyResolution.error)
                }
            }

            val options = ClientOptions(
                api = ClientOptions.Api(
                    env = environment,
                    isSecure = true
                ),
                appContext = this@XmtpEngine.appContext,
                dbDirectory = dbDirectory.absolutePath,
                dbEncryptionKey = dekBytes,
                // Desactivamos Device Sync remoto para usar solo transferencia directa telefono a telefono.
                deviceSyncEnabled = false
            )

            Log.i(TAG, "Initializing XMTP client for env=$environment")
            runCatching { Client.register(GroupUpdatedCodec()) }
            runCatching { Client.register(ReadReceiptCodec()) }
            runCatching { Client.register(ProfileNicknameCodec()) }
            runCatching { Client.register(CallSignalCodec()) }
            val xmtpClient = Client.create(account = signer, options = options)
            Log.i(TAG, "XMTP client ready [env=$environment]")

            XmtpEngineState.Ready(xmtpClient)

        } catch (e: CancellationException) {
            throw e
        } catch (e: UserRejectedSignatureException) {
            XmtpEngineState.Error(
                XmtpInitError.SignatureRejected(
                    e.message ?: "User rejected the signature request",
                    e
                )
            )
        } catch (e: XmtpSignatureException) {
            XmtpEngineState.Error(XmtpInitError.SignatureRejected(e.message ?: "Signature rejected", e))
        } catch (e: InternalException) {
            mapExceptionToState(e)
        } catch (e: Exception) {
            mapExceptionToState(e)
        } finally {
            dekToZero?.fill(0x00)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Domain Error Mapping
    // ────────────────────────────────────────────────────────────────────────

    private fun mapExceptionToState(e: Exception): XmtpEngineState.Error {
        val message = e.message?.lowercase(Locale.ROOT) ?: ""
        Log.e(TAG, "XMTP client initialisation failed [${e.javaClass.simpleName}]")

        val error = when {
            isSignatureError(message) -> XmtpInitError.SignatureRejected(
                message = "Wallet refused or produced an invalid EIP-191 signature: ${e.message}",
                cause = e
            )

            CryptoUtils.isNetworkThrowable(e) -> XmtpInitError.NetworkFailure(
                message = "XMTP network unreachable or gRPC handshake failed: ${e.message}",
                cause = e
            )

            isDatabaseError(message) -> XmtpInitError.DatabaseError(
                message = "libxmtp could not open or decrypt the local store: ${e.message}",
                cause = e
            )

            isIdentityError(message) -> XmtpInitError.IdentityError(
                message = "Inbox identity registration or association failed: ${e.message}",
                cause = e
            )

            e is InternalException -> XmtpInitError.DatabaseError(
                message = "Native Rust core panic during MLS operation: ${e.message}",
                cause = e
            )

            else -> XmtpInitError.Unknown(
                message = "Unexpected error during XMTP initialisation: ${e.message}",
                cause = e
            )
        }

        return XmtpEngineState.Error(error)
    }

    private fun isSignatureError(msg: String): Boolean = msg.containsAny(SIGNATURE_KEYWORDS)

    private fun isDatabaseError(msg: String): Boolean = msg.containsAny(DATABASE_KEYWORDS)

    private fun isIdentityError(msg: String): Boolean = msg.containsAny(IDENTITY_KEYWORDS)

    private fun String.containsAny(keywords: Set<String>): Boolean = keywords.any { this.contains(it) }

    private fun resolveDatabaseEncryptionKey(customKey: ByteArray?): KeyResolutionResult {
        if (customKey != null) {
            return KeyResolutionResult.Success(customKey, shouldZeroize = true)
        }
        return when (val keyResult = keystoreManager.getOrCreateDatabaseKey()) {
            is KeystoreResult.Success -> {
                val rawDek = keyResult.value
                val cloned = rawDek.clone()
                rawDek.fill(0x00)
                KeyResolutionResult.Success(cloned, shouldZeroize = true)
            }

            is KeystoreResult.Failure -> {
                KeyResolutionResult.Failure(
                    XmtpInitError.DatabaseError(
                        message = "Keystore failed to provision or unwrap DEK: ${keyResult.message}",
                        cause = keyResult.cause
                    )
                )
            }
        }
    }

    private fun ensureDirectoryAccessible(directory: File) {
        if (!directory.exists()) {
            check(directory.mkdirs()) {
                "Failed to create XMTP db directory: ${directory.absolutePath}"
            }
        }
        check(directory.isDirectory && directory.canWrite()) {
            "XMTP db directory is not writable: ${directory.absolutePath}"
        }
    }

    companion object {
        private const val TAG = "XmtpEngine"

        private val SIGNATURE_KEYWORDS = setOf(
            "signature", "sign", "rejected", "user denied", "user canceled", "user cancelled"
        )
        private val DATABASE_KEYWORDS = setOf(
            "database", "sqlcipher", "sqlite", "db ", "storage", "keystore",
            "schema", "migration", "corrupt", "locked", "busy"
        )
        private val IDENTITY_KEYWORDS = setOf(
            "identity", "inbox", "association", "installation", "member"
        )
    }

    private sealed interface KeyResolutionResult {
        data class Success(val key: ByteArray, val shouldZeroize: Boolean) : KeyResolutionResult {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Success) return false
                return key.contentEquals(other.key) && shouldZeroize == other.shouldZeroize
            }

            override fun hashCode(): Int {
                var result = key.contentHashCode()
                result = 31 * result + shouldZeroize.hashCode()
                return result
            }
        }

        data class Failure(val error: XmtpInitError.DatabaseError) : KeyResolutionResult
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  State Model (Sealed Hierarchy)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Represents the lifecycle state of the [XmtpEngine].
 */
sealed interface XmtpEngineState {

    /** Engine is idle (initial state or following [XmtpEngine.shutdown]). */
    data object Idle : XmtpEngineState

    /** Client initialization is underway asynchronously. */
    data object Loading : XmtpEngineState

    /** Client is fully initialized and operational. */
    data class Ready(val client: Client) : XmtpEngineState

    /** Initialization failed with a strongly-typed domain error. */
    data class Error(val error: XmtpInitError) : XmtpEngineState
}

// ────────────────────────────────────────────────────────────────────────────
//  Domain Error Hierarchy
// ────────────────────────────────────────────────────────────────────────────

/**
 * Exhaustive error hierarchy for XMTP initialization failures.
 */
sealed interface XmtpInitError {

    val message: String
    val cause: Throwable

    /** Wallet refused or produced an invalid EIP-191 signature. */
    data class SignatureRejected(
        override val message: String,
        override val cause: Throwable
    ) : XmtpInitError

    /** Network connection, DNS, or gRPC handshake failure. */
    data class NetworkFailure(
        override val message: String,
        override val cause: Throwable
    ) : XmtpInitError

    /** Local SQLCipher database or Keystore DEK unwrapping failure. */
    data class DatabaseError(
        override val message: String,
        override val cause: Throwable
    ) : XmtpInitError

    /** Inbox identity association or MLS registration failure on XMTP network ledger. */
    data class IdentityError(
        override val message: String,
        override val cause: Throwable
    ) : XmtpInitError

    /** Unclassified or unexpected failure. */
    data class Unknown(
        override val message: String,
        override val cause: Throwable
    ) : XmtpInitError
}

/**
 * Thrown internally when a signer explicitly rejects the request.
 */
internal class XmtpSignatureException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

// Leemos el nombre sincrono del grupo para UI y notificaciones sin convertir composables en suspend.
@Suppress("DEPRECATION")
internal fun org.xmtp.android.library.Group.cachedGroupName(): String = name
