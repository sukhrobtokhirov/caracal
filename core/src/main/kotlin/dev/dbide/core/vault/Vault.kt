package dev.dbide.core.vault

import dev.dbide.core.connections.Secret
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the vault is in its lifecycle. */
enum class VaultState {
    /** No master password has been chosen yet. */
    SETUP_REQUIRED,

    /** A master password exists, but its key is not in memory. */
    LOCKED,

    /** The derived key is held for this process. */
    UNLOCKED,
}

/** The slice of the configuration store the vault needs. */
interface MetadataStore {
    suspend fun getMetadata(key: String): ByteArray?

    suspend fun putMetadata(key: String, value: ByteArray)
}

/**
 * Owns the master key for the process lifetime.
 *
 * Nothing here writes the password, the derived key, or a plaintext verifier to
 * disk. What is stored is the salt, the cost parameters, and a sealed probe.
 */
class Vault(
    private val store: MetadataStore,
    private val params: KdfParams = KdfParams.DEFAULT,
    private val clock: () -> Instant = Instant::now,
    /** Argon2id is CPU-bound, not I/O-bound: it belongs off the UI and off the IO pool. */
    private val derivationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()

    @Volatile
    private var key: ByteArray? = null
    private var failures = 0
    private var lockedUntil: Instant? = null

    /** Whether the derived key is currently held. */
    val isUnlocked: Boolean get() = key != null

    /** Reports whether setup is needed and whether the key is held. */
    suspend fun state(): VaultState {
        if (isUnlocked) return VaultState.UNLOCKED
        return if (store.getMetadata(META_VERIFIER) == null) {
            VaultState.SETUP_REQUIRED
        } else {
            VaultState.LOCKED
        }
    }

    /**
     * Chooses the master password on first run and leaves the vault unlocked.
     *
     * The caller keeps ownership of [password] and should clear it afterwards.
     */
    suspend fun setUp(password: Secret) {
        if (password.exposeChars().size < MIN_PASSWORD_LENGTH) throw WeakPasswordException()
        if (store.getMetadata(META_VERIFIER) != null) throw AlreadySetUpException()

        val salt = Kdf.newSalt()
        val derived = withContext(derivationDispatcher) { Kdf.deriveKey(password, salt, params) }
        try {
            val verifier = Seal.sealVerifier(derived)
            // Order matters: the verifier is written last, so a crash mid-setup leaves
            // the vault needing setup rather than permanently unopenable.
            store.putMetadata(META_SALT, salt)
            store.putMetadata(META_PARAMS, params.encode())
            store.putMetadata(META_VERIFIER, verifier)
        } catch (failure: Throwable) {
            derived.wipe()
            throw failure
        }
        mutex.withLock {
            replaceKey(derived)
            failures = 0
            lockedUntil = null
        }
    }

    /**
     * Derives a key from [password] and checks it against the stored verifier. The
     * vault unlocks only if authenticated decryption succeeds.
     */
    suspend fun unlock(password: Secret) {
        mutex.withLock {
            lockedUntil?.let { until ->
                val wait = until.toEpochMilli() - clock().toEpochMilli()
                if (wait > 0) throw TooManyAttemptsException(wait.milliseconds)
            }
        }

        val salt = store.getMetadata(META_SALT)
        val encodedParams = store.getMetadata(META_PARAMS)
        val verifier = store.getMetadata(META_VERIFIER)
        if (salt == null || encodedParams == null || verifier == null) throw NotSetUpException()

        val stored = KdfParams.decode(encodedParams)
        val derived = withContext(derivationDispatcher) { Kdf.deriveKey(password, salt, stored) }

        if (!Seal.verifies(derived, verifier)) {
            derived.wipe()
            recordFailure()
            throw WrongPasswordException()
        }
        mutex.withLock {
            replaceKey(derived)
            failures = 0
            lockedUntil = null
        }
    }

    /**
     * Discards the derived key. Live database clients are the caller's problem —
     * [dev.dbide.core.connections.ConnectionService] closes them.
     */
    suspend fun lock() = mutex.withLock { replaceKey(null) }

    /** Seals a connection password under the master key. */
    suspend fun seal(identity: SecretIdentity, secret: Secret): ByteArray =
        Seal.seal(borrowKey(), identity, secret)

    /** Opens a sealed connection password. */
    suspend fun open(identity: SecretIdentity, envelope: ByteArray): Secret =
        Seal.open(borrowKey(), identity, envelope)

    /**
     * The live key. Callers must not retain or modify it — it is wiped in place when
     * the vault locks.
     */
    private suspend fun borrowKey(): ByteArray =
        mutex.withLock { key ?: throw VaultLockedException() }

    private fun replaceKey(replacement: ByteArray?) {
        key?.wipe()
        key = replacement
    }

    private suspend fun recordFailure() = mutex.withLock {
        failures++
        if (failures < MAX_FAILURES_BEFORE_COOLDOWN) return@withLock
        // Each failure past the threshold doubles the wait, up to the cap.
        val doublings = failures - MAX_FAILURES_BEFORE_COOLDOWN
        val cooldown = if (doublings >= 30) MAX_COOLDOWN else minOf(BASE_COOLDOWN * (1 shl doublings), MAX_COOLDOWN)
        lockedUntil = clock().plusMillis(cooldown.inWholeMilliseconds)
    }

    companion object {
        /** Metadata keys the vault owns. */
        const val META_SALT = "kdf_salt"
        const val META_PARAMS = "kdf_params"
        const val META_VERIFIER = "master_verifier"

        /**
         * Guards against an accidentally empty or trivial password. There are
         * deliberately no composition rules: against someone who can read the
         * configuration file, the Argon2id cost and the password's length do the work,
         * and character-class rules mostly produce passwords people write down.
         */
        const val MIN_PASSWORD_LENGTH = 8

        /**
         * Argon2id already makes each guess expensive; this stops a script from
         * spending the whole CPU budget on attempts.
         */
        const val MAX_FAILURES_BEFORE_COOLDOWN = 5
        val BASE_COOLDOWN = 30.seconds
        val MAX_COOLDOWN = 15.minutes
    }
}
