/**
 * Key derivation and secret sealing.
 *
 * Threat model: someone who can read the configuration file on this machine.
 * Argon2id makes an offline guess expensive; AES-GCM makes a silently modified
 * record impossible. Neither defends against someone who already controls the
 * running process.
 */
package dev.caracal.core.vault

import dev.caracal.core.connections.Secret
import java.security.SecureRandom
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** The derivation scheme. Bump it if the algorithm changes — not merely its cost. */
const val KDF_VERSION = 1

/** The per-installation Argon2id salt length. */
const val SALT_LENGTH = 16

/** The derived key length: AES-256. */
const val KEY_LENGTH = 32

/**
 * Argon2id cost, stored alongside the salt so a future cost increase can still open
 * old data. [DEFAULT] targets roughly a tenth of a second on a modern laptop while
 * costing an attacker 64 MiB per guess.
 */
data class KdfParams(
    val version: Int = KDF_VERSION,
    /** Number of Argon2id passes. */
    val iterations: Int = 3,
    /** Memory cost in kibibytes. */
    val memoryKib: Int = 64 * 1024,
    /** Degree of parallelism. */
    val parallelism: Int = 4,
    /** Derived key length in bytes. */
    val keyLength: Int = KEY_LENGTH,
) {
    /**
     * Rejects parameters this build cannot use or that are dangerously weak.
     *
     * @throws UnsupportedKdfException
     */
    fun validate(): KdfParams {
        fun reject(why: String): Nothing = throw UnsupportedKdfException(why)
        when {
            version != KDF_VERSION -> reject("key derivation version $version is not supported")
            iterations < 1 -> reject("the time cost must be at least 1")
            memoryKib < 8 * 1024 -> reject("the memory cost must be at least 8 MiB")
            parallelism < 1 -> reject("the parallelism must be at least 1")
            keyLength != KEY_LENGTH -> reject("the key length must be $KEY_LENGTH bytes")
        }
        return this
    }

    /**
     * Encodes the parameters for storage. Self-describing on purpose: a field added
     * later is readable, and a field removed is a recognisable failure rather than a
     * silent misparse.
     */
    fun encode(): ByteArray =
        "argon2id v=$version t=$iterations m=$memoryKib p=$parallelism len=$keyLength"
            .toByteArray(Charsets.US_ASCII)

    companion object {
        val DEFAULT = KdfParams()

        /** Cheap parameters for tests. Never write these to a real installation. */
        val TESTING = KdfParams(iterations = 1, memoryKib = 8 * 1024, parallelism = 1)

        fun decode(encoded: ByteArray): KdfParams {
            val text = String(encoded, Charsets.US_ASCII).trim()
            val fields = text.split(' ')
            if (fields.firstOrNull() != "argon2id") {
                throw UnsupportedKdfException("the stored key derivation parameters are unreadable")
            }
            val values = fields.drop(1).associate { field ->
                val (key, value) = field.split('=', limit = 2).let {
                    if (it.size == 2) it else throw UnsupportedKdfException("the stored key derivation parameters are unreadable")
                }
                key to (value.toIntOrNull() ?: throw UnsupportedKdfException("the stored key derivation parameters are unreadable"))
            }
            fun field(name: String): Int = values[name]
                ?: throw UnsupportedKdfException("the stored key derivation parameters are missing \"$name\"")
            return KdfParams(
                version = field("v"),
                iterations = field("t"),
                memoryKib = field("m"),
                parallelism = field("p"),
                keyLength = field("len"),
            ).validate()
        }
    }
}

/** Derivation and randomness. Stateless; the key it returns is the caller's to hold. */
object Kdf {
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_LENGTH).also(random::nextBytes)

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also(random::nextBytes)

    /**
     * Turns a master password into the AES key. The password stays a [CharArray]
     * throughout: BouncyCastle encodes it directly, so no `String` copy is created
     * for the GC to keep.
     *
     * The returned array is the process's most sensitive value. Callers keep exactly
     * one copy and [wipe] it when done.
     */
    fun deriveKey(password: Secret, salt: ByteArray, params: KdfParams): ByteArray {
        params.validate()
        require(salt.size >= SALT_LENGTH) { "the salt is too short" }

        val argon = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKib)
            .withParallelism(params.parallelism)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator().apply { init(argon) }
        val key = ByteArray(params.keyLength)
        generator.generateBytes(password.exposeChars(), key)
        return key
    }
}

/**
 * Overwrites a sensitive array. The JVM cannot guarantee the bytes never reached
 * another page, but clearing the copy we control is still worth doing.
 */
fun ByteArray.wipe() = fill(0)
