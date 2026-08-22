package dev.caracal.core.vault

import dev.caracal.engine.api.SecretBundle
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The record format, tested at the bytes.
 *
 * Everything here is plaintext: sealing is [SealTest]'s subject and encryption is
 * not what makes a stored credential readable in three years' time. This is.
 */
class VaultRecordTest {

    @Test
    fun `every shape a secret can take survives the round trip`() {
        val cases = listOf(
            SecretBundle.None,
            SecretBundle.Password("hunter2".toCharArray()),
            SecretBundle.UserPassword("reader", "hunter2".toCharArray()),
            SecretBundle.ClientCertificate(byteArrayOf(0, 1, 2, -1, 127), "keystore-pass".toCharArray()),
            SecretBundle.ConnectionString("postgresql://reader:hunter2@db/orders".toCharArray()),
            SecretBundle.Token("ya29.a0Af".toCharArray(), Instant.parse("2026-09-01T12:00:00Z")),
            SecretBundle.Token("no-expiry".toCharArray(), expiresAt = null),
        )

        cases.forEach { original ->
            val record = decodeRecord(encodeRecord(original))

            assertEquals(RECORD_VERSION, record.schemaVersion)
            assertEquals(describe(original), describe(record.secret), "round trip changed $original")
        }
    }

    @Test
    fun `an empty field is a field, not an absent one`() {
        // An empty password is a real password — PostgreSQL will authenticate with
        // one — and a record that loses the difference dials as somebody else.
        val record = decodeRecord(encodeRecord(SecretBundle.UserPassword("reader", CharArray(0))))

        val pair = assertIs<SecretBundle.UserPassword>(record.secret)
        assertEquals("reader", pair.user)
        assertEquals(0, pair.password.size)
    }

    @Test
    fun `every character field survives UTF-8 that is not ASCII`() {
        val text = "sürprïse-密码-🔐"

        val record = decodeRecord(encodeRecord(SecretBundle.UserPassword(text, text.toCharArray())))

        val pair = assertIs<SecretBundle.UserPassword>(record.secret)
        assertEquals(text, pair.user)
        assertEquals(text, String(pair.password))
    }

    @Test
    fun `a v1 record is a password, and stays readable forever`() {
        // The exact bytes a v0.1.0 installation sealed: a version byte, then UTF-8.
        val legacy = byteArrayOf(RECORD_VERSION_LEGACY.toByte()) + "hunter2".toByteArray(Charsets.UTF_8)

        val record = decodeRecord(legacy)

        assertEquals(RECORD_VERSION_LEGACY, record.schemaVersion)
        assertEquals(false, record.isCurrent)
        assertEquals("hunter2", String(assertIs<SecretBundle.Password>(record.secret).password))
    }

    @Test
    fun `a v1 record with no body is nothing stored, not an empty password`() {
        val record = decodeRecord(byteArrayOf(RECORD_VERSION_LEGACY.toByte()))

        assertEquals(SecretBundle.None, record.secret)
    }

    @Test
    fun `a token with no expiry keeps none rather than gaining one`() {
        val record = decodeRecord(encodeRecord(SecretBundle.Token("t".toCharArray(), expiresAt = null)))

        assertNull(assertIs<SecretBundle.Token>(record.secret).expiresAt)
    }

    @Test
    fun `the current version is what encoding writes`() {
        assertEquals(RECORD_VERSION.toByte(), encodeRecord(SecretBundle.None)[0])
    }

    @Test
    fun `a version this build does not know is a failure, not a guess`() {
        val failure = assertThrows<MalformedEnvelopeException> { decodeRecord(byteArrayOf(9, 0)) }

        // The number is in the message: it is what tells the user the file was
        // written by a newer build rather than damaged.
        assertEquals(true, failure.safeMessage.contains("9"))
    }

    @Test
    fun `a kind this build does not know is a failure, not a guess`() {
        assertThrows<MalformedEnvelopeException> {
            decodeRecord(byteArrayOf(RECORD_VERSION.toByte(), 99))
        }
    }

    @Test
    fun `an empty record is a failure rather than an index out of bounds`() {
        assertThrows<MalformedEnvelopeException> { decodeRecord(ByteArray(0)) }
        assertThrows<MalformedEnvelopeException> { decodeRecord(byteArrayOf(RECORD_VERSION.toByte())) }
    }

    @Test
    fun `a truncated record is a failure rather than a partial secret`() {
        val whole = encodeRecord(SecretBundle.UserPassword("reader", "hunter2".toCharArray()))

        // Every prefix that is not the whole thing. A record either decodes or does
        // not; there is no length at which it half-decodes into a shorter password.
        (2 until whole.size).forEach { length ->
            assertThrows<MalformedEnvelopeException>("a $length-byte prefix decoded") {
                decodeRecord(whole.copyOf(length))
            }
        }
    }

    @Test
    fun `a length longer than the record is refused before anything is allocated`() {
        // 0x7fffffff bytes of field, in a nine-byte record.
        val hostile = byteArrayOf(RECORD_VERSION.toByte(), 1, 0x7f, -1, -1, -1, 1, 2, 3)

        assertThrows<MalformedEnvelopeException> { decodeRecord(hostile) }
    }

    @Test
    fun `a field this build does not recognise is a failure, not a prefix`() {
        // A password record with a second field bolted on: written by a build that
        // knows something about this credential that this one does not. Reading the
        // first field and dialing with it is the answer that must not happen.
        val extended = encodeRecord(SecretBundle.Password("hunter2".toCharArray())) +
            byteArrayOf(0, 0, 0, 1, 42)

        assertThrows<MalformedEnvelopeException> { decodeRecord(extended) }
    }

    @Test
    fun `wiping clears every sensitive array a bundle holds`() {
        val keyStore = byteArrayOf(1, 2, 3)
        val certificate = SecretBundle.ClientCertificate(keyStore, "passphrase".toCharArray())

        certificate.wipe()

        assertContentEquals(ByteArray(3), certificate.keyStore)
        assertEquals("          ", String(certificate.passphrase))
    }

    /** A comparable rendering: the arms hold arrays, whose `equals` is identity. */
    private fun describe(secret: SecretBundle): String = when (secret) {
        is SecretBundle.None -> "none"
        is SecretBundle.Password -> "password/${String(secret.password)}"
        is SecretBundle.UserPassword -> "user_password/${secret.user}/${String(secret.password)}"
        is SecretBundle.ClientCertificate ->
            "client_certificate/${secret.keyStore.toList()}/${String(secret.passphrase)}"

        is SecretBundle.ConnectionString -> "connection_string/${String(secret.value)}"
        is SecretBundle.Token -> "token/${String(secret.value)}/${secret.expiresAt}"
    }
}
