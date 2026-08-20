package dev.dbide.core.vault

import dev.dbide.core.connections.Secret
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SealTest {
    private val key = Kdf.randomBytes(KEY_LENGTH)
    private val identity = SecretIdentity("id-1", "postgres")

    @Test
    fun `a sealed secret comes back exactly as it went in`() {
        val envelope = Seal.seal(key, identity, Secret("hunter2"))

        assertEquals("hunter2", Seal.open(key, identity, envelope).expose())
    }

    @Test
    fun `an empty secret round trips, because an empty password is a real password`() {
        val envelope = Seal.seal(key, identity, Secret(""))

        assertEquals("", Seal.open(key, identity, envelope).expose())
    }

    @Test
    fun `a non-ASCII secret survives the UTF-8 round trip`() {
        val password = "sürprïse-密码-🔐"

        val envelope = Seal.seal(key, identity, Secret(password))

        assertEquals(password, Seal.open(key, identity, envelope).expose())
    }

    @Test
    fun `sealing the same secret twice produces different bytes`() {
        val first = Seal.seal(key, identity, Secret("hunter2"))
        val second = Seal.seal(key, identity, Secret("hunter2"))

        assertFalse(first.contentEquals(second), "a repeated nonce would leak equality of secrets")
        // Only the nonce and ciphertext differ; the version prefix is the same.
        assertEquals(first[0], second[0])
    }

    @Test
    fun `the envelope never contains the plaintext`() {
        val envelope = Seal.seal(key, identity, Secret("hunter2"))

        assertFalse(String(envelope, Charsets.ISO_8859_1).contains("hunter2"))
    }

    @Test
    fun `the envelope is version, nonce, then ciphertext`() {
        val envelope = Seal.seal(key, identity, Secret("hunter2"))

        assertEquals(ENVELOPE_VERSION, envelope[0])
        assertTrue(envelope.size > 1 + NONCE_LENGTH)
    }

    @Test
    fun `another key cannot open it`() {
        val envelope = Seal.seal(key, identity, Secret("hunter2"))

        assertThrows<SecretUnreadableException> {
            Seal.open(Kdf.randomBytes(KEY_LENGTH), identity, envelope)
        }
    }

    @Test
    fun `a modified nonce is detected`() {
        val envelope = Seal.seal(key, identity, Secret("hunter2"))
        envelope[3] = (envelope[3] + 1).toByte()

        assertThrows<SecretUnreadableException> { Seal.open(key, identity, envelope) }
    }

    @Test
    fun `a modified ciphertext is detected`() {
        val envelope = Seal.seal(key, identity, Secret("hunter2"))
        envelope[envelope.size - 1] = (envelope.last() + 1).toByte()

        assertThrows<SecretUnreadableException> { Seal.open(key, identity, envelope) }
    }

    @Test
    fun `a ciphertext moved to another connection will not open`() {
        val envelope = Seal.seal(key, identity, Secret("hunter2"))

        assertThrows<SecretUnreadableException> {
            Seal.open(key, SecretIdentity("id-2", "postgres"), envelope)
        }
    }

    @Test
    fun `a ciphertext reinterpreted under another engine will not open`() {
        val envelope = Seal.seal(key, identity, Secret("hunter2"))

        assertThrows<SecretUnreadableException> {
            Seal.open(key, SecretIdentity("id-1", "redis"), envelope)
        }
    }

    @Test
    fun `bytes that are not an envelope are rejected rather than read as plaintext`() {
        assertThrows<MalformedEnvelopeException> { Seal.open(key, identity, ByteArray(4)) }
        assertThrows<MalformedEnvelopeException> {
            Seal.open(key, identity, byteArrayOf(9) + ByteArray(NONCE_LENGTH + 16))
        }
    }

    @Test
    fun `a key of the wrong length is refused before any cipher runs`() {
        assertThrows<UnsupportedKdfException> { Seal.seal(ByteArray(16), identity, Secret("hunter2")) }
    }

    @Test
    fun `the verifier opens under its own key and no other`() {
        val verifier = Seal.sealVerifier(key)

        assertTrue(Seal.verifies(key, verifier))
        assertFalse(Seal.verifies(Kdf.randomBytes(KEY_LENGTH), verifier))
    }

    @Test
    fun `two verifiers sealed under the same key still differ`() {
        assertFalse(Seal.sealVerifier(key).contentEquals(Seal.sealVerifier(key)))
    }

    @Test
    fun `the additional data is version-prefixed so it cannot be confused with another format`() {
        val aad = SecretIdentity("id-1", "postgres").aad()

        assertEquals(ENVELOPE_VERSION, aad[0])
        assertContentEquals(
            byteArrayOf(ENVELOPE_VERSION) + "id-1/postgres".toByteArray(),
            aad,
        )
    }
}
