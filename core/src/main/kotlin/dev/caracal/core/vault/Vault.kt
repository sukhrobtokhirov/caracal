package dev.caracal.core.vault

import dev.caracal.core.connections.Secret
import dev.caracal.engine.api.SecretBundle
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
     *
     * A salt with no verifier is refused rather than overwritten. The two ways to
     * reach that state look identical from here and want opposite handling: a crash
     * between the two writes in this method, where nothing has been sealed yet and
     * re-keying costs nothing — and a damaged or partly restored file, where every
     * stored credential is sealed under the salt about to be replaced and re-keying
     * makes all of them permanently unopenable, silently, behind the ordinary
     * first-run screen. Only a caller that can see the connection records can tell
     * the two apart, so [replaceOrphanedMetadata] is how one says it has looked.
     */
    suspend fun setUp(password: Secret, replaceOrphanedMetadata: Boolean = false) {
        if (password.exposeChars().size < MIN_PASSWORD_LENGTH) throw WeakPasswordException()

        // The whole sequence is serialized. Two callers that each read a null verifier
        // and then raced through the writes could leave one caller's salt beside the
        // other's verifier, and that pair is not openable by any password.
        mutex.withLock {
            if (store.getMetadata(META_VERIFIER) != null) throw AlreadySetUpException()
            // A salt with no verifier is damage, not a first run. Choosing a new
            // password here would write a new salt over the old one, and every
            // connection secret already sealed under the old key would become
            // permanently unopenable — silently, and looking exactly like a fresh
            // installation. Refuse instead, and leave the metadata for recovery.
            val orphaned = store.getMetadata(META_SALT) != null || store.getMetadata(META_PARAMS) != null
            if (orphaned && !replaceOrphanedMetadata) throw VaultDamagedException()

            val salt = Kdf.newSalt()
            val derived = withContext(derivationDispatcher) { Kdf.deriveKey(password, salt, params) }
            try {
                val verifier = Seal.sealVerifier(derived)
                // Order matters: the verifier is written last, so a crash mid-setup
                // leaves the vault needing setup rather than permanently unopenable.
                store.putMetadata(META_SALT, salt)
                store.putMetadata(META_PARAMS, params.encode())
                store.putMetadata(META_VERIFIER, verifier)
            } catch (failure: Throwable) {
                derived.wipe()
                throw failure
            }
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

        // A verifier that is not an envelope at all leaves `verifies` as a
        // MalformedEnvelopeException rather than a false, so a damaged file is
        // reported as damage instead of counting against the attempt budget.
        val opened = try {
            Seal.verifies(derived, verifier)
        } catch (failure: Throwable) {
            derived.wipe()
            throw failure
        }
        if (!opened) {
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
     * [dev.caracal.core.connections.ConnectionService] closes them.
     */
    suspend fun lock() = mutex.withLock { replaceKey(null) }

    /** Seals a connection's credential under the master key, in the current record format. */
    suspend fun seal(identity: SecretIdentity, secret: SecretBundle): ByteArray =
        withKey { key -> Seal.seal(key, identity, secret) }

    /**
     * Opens a sealed credential, whichever record version it was written in.
     *
     * The version is not returned, because no caller outside this class has any
     * business branching on it: a record that is behind is one [unlock] rewrites,
     * and one that is current is indistinguishable from it here.
     */
    suspend fun open(identity: SecretIdentity, envelope: ByteArray): SecretBundle =
        withKey { key -> Seal.open(key, identity, envelope).secret }

    /**
     * Runs [body] with the live key, holding the lock for as long as it is in use.
     *
     * The lock is what keeps [replaceKey] from wiping the array mid-cipher. Handing
     * the key out and releasing the lock — the shorter thing to write — let a
     * concurrent [lock] zero it while AES was reading it, and the envelope that came
     * back was sealed under a key of zeroes: unopenable afterwards, and decryptable
     * by anyone holding the configuration file. `VaultTest` has the regression.
     *
     * [body] is deliberately not `suspend`: nothing may park here with the key
     * exposed and the mutex held.
     */
    private suspend fun <T> withKey(body: (ByteArray) -> T): T =
        mutex.withLock { body(key ?: throw VaultLockedException()) }

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
