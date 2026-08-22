package dev.caracal.core.vault

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.EngineId
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.secretOfBytes
import dev.caracal.core.connections.toBytes
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The sealed-secret format on disk. */
const val ENVELOPE_VERSION: Byte = 1

/** The AES-GCM nonce length: 96 bits, the size GCM is defined for. */
const val NONCE_LENGTH = 12

/**
 * The plaintext structure inside an envelope. A leading version byte means another
 * sensitive field can be added later without inventing a second crypto format.
 */
const val PAYLOAD_VERSION: Byte = 1

/** The GCM authentication tag length, in bits. */
private const val TAG_BITS = 128

/**
 * The connection a secret belongs to. It is authenticated but not encrypted, so a
 * ciphertext moved to another record fails to open rather than silently decrypting
 * under the wrong server.
 */
data class SecretIdentity(val connectionId: String, val engine: String) {
    /** The version prefix keeps the additional data unambiguous if the format changes. */
    internal fun aad(): ByteArray =
        byteArrayOf(ENVELOPE_VERSION) + "$connectionId/$engine".toByteArray(Charsets.UTF_8)

    companion object {
        fun of(config: ConnectionConfig) = SecretIdentity(config.id.value, config.engineId.value)

        fun of(id: ConnectionId, engine: EngineId) = SecretIdentity(id.value, engine.value)

        /** The fixed identity of the unlock verifier. */
        internal val VERIFIER = SecretIdentity("master", "verifier")
    }
}

/**
 * AES-256-GCM sealing under a derived key.
 *
 * Envelope layout: `version(1) || nonce(12) || ciphertext+tag`.
 */
object Seal {
    fun seal(key: ByteArray, identity: SecretIdentity, secret: Secret): ByteArray {
        val plaintext = payload(secret)
        try {
            val nonce = Kdf.randomBytes(NONCE_LENGTH)
            val sealed = gcm(Cipher.ENCRYPT_MODE, key, nonce, identity).doFinal(plaintext)
            return byteArrayOf(ENVELOPE_VERSION) + nonce + sealed
        } finally {
            plaintext.wipe()
        }
    }

    /**
     * Opens an envelope. Every failure is a [SecretUnreadableException] or a
     * [MalformedEnvelopeException]; the bytes are never reinterpreted as plaintext.
     */
    fun open(key: ByteArray, identity: SecretIdentity, envelope: ByteArray): Secret {
        if (envelope.size < 1 + NONCE_LENGTH + 1) throw MalformedEnvelopeException()
        if (envelope[0] != ENVELOPE_VERSION) {
            throw MalformedEnvelopeException("unknown envelope version ${envelope[0]}")
        }
        val nonce = envelope.copyOfRange(1, 1 + NONCE_LENGTH)
        val ciphertext = envelope.copyOfRange(1 + NONCE_LENGTH, envelope.size)

        val plaintext = try {
            gcm(Cipher.DECRYPT_MODE, key, nonce, identity).doFinal(ciphertext)
        } catch (failure: AEADBadTagException) {
            // A wrong key, a tampered record, and a ciphertext copied from another
            // connection are deliberately indistinguishable here.
            throw SecretUnreadableException(failure)
        } catch (failure: BadPaddingException) {
            throw SecretUnreadableException(failure)
        } catch (failure: IllegalBlockSizeException) {
            throw SecretUnreadableException(failure)
        }

        try {
            // An envelope whose plaintext is empty could only be crafted by someone
            // who already holds the key, but the contract here is that every failure
            // leaves as a VaultException rather than an index out of bounds.
            if (plaintext.isEmpty()) throw SecretUnreadableException()
            if (plaintext[0] != PAYLOAD_VERSION) {
                throw MalformedEnvelopeException("unknown payload version ${plaintext[0]}")
            }
            // The copy is the whole decrypted secret in a second array. Wiping only
            // `plaintext` would leave that one for the GC to hand to whatever reuses
            // the page, which is the leak this class exists to avoid.
            val payload = plaintext.copyOfRange(1, plaintext.size)
            try {
                return secretOfBytes(payload)
            } finally {
                payload.wipe()
            }
        } finally {
            plaintext.wipe()
        }
    }

    /**
     * Seals a random probe so a later unlock can tell a wrong master password from
     * corrupted connection data.
     */
    fun sealVerifier(key: ByteArray): ByteArray {
        val probe = Secret(Kdf.randomBytes(VERIFIER_BYTES).toHexChars())
        try {
            return seal(key, SecretIdentity.VERIFIER, probe)
        } finally {
            probe.clear()
        }
    }

    /**
     * Reports whether [key] opens the stored verifier.
     *
     * Only a failed authentication answers `false`. A verifier that is not an
     * envelope at all — truncated by a partial write, or carrying a version this
     * build does not know — is the one case that must not be reported as a wrong
     * password: doing so tells the user their correct password is wrong, forever,
     * and spends the unlock cooldown on a file that no password can open. Those
     * leave as [MalformedEnvelopeException] or [UnsupportedKdfException] instead.
     */
    fun verifies(key: ByteArray, verifier: ByteArray): Boolean =
        try {
            open(key, SecretIdentity.VERIFIER, verifier).clear()
            true
        } catch (_: SecretUnreadableException) {
            false
        }

    private fun payload(secret: Secret): ByteArray {
        val bytes = secret.toBytes()
        try {
            return byteArrayOf(PAYLOAD_VERSION) + bytes
        } finally {
            bytes.wipe()
        }
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, identity: SecretIdentity): Cipher {
        if (key.size != KEY_LENGTH) throw UnsupportedKdfException("the master key is the wrong length")
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(identity.aad())
        }
    }

    private const val VERIFIER_BYTES = 32

    /** Hex keeps the probe printable, so nothing downstream has to handle stray bytes. */
    private fun ByteArray.toHexChars(): CharArray {
        val digits = "0123456789abcdef"
        val out = CharArray(size * 2)
        forEachIndexed { index, byte ->
            out[index * 2] = digits[(byte.toInt() shr 4) and 0xf]
            out[index * 2 + 1] = digits[byte.toInt() and 0xf]
        }
        wipe()
        return out
    }
}
