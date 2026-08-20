package dev.dbide.core.vault

import dev.dbide.core.connections.Secret
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KdfTest {
    private val salt = Kdf.newSalt()

    @Test
    fun `the same password and salt derive the same key`() {
        val first = Kdf.deriveKey(Secret("correct-horse"), salt, KdfParams.TESTING)
        val second = Kdf.deriveKey(Secret("correct-horse"), salt, KdfParams.TESTING)

        assertContentEquals(first, second)
        assertEquals(KEY_LENGTH, first.size)
    }

    @Test
    fun `a different password derives a different key`() {
        val first = Kdf.deriveKey(Secret("correct-horse"), salt, KdfParams.TESTING)
        val second = Kdf.deriveKey(Secret("correct-horsf"), salt, KdfParams.TESTING)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `a different salt derives a different key`() {
        val first = Kdf.deriveKey(Secret("correct-horse"), salt, KdfParams.TESTING)
        val second = Kdf.deriveKey(Secret("correct-horse"), Kdf.newSalt(), KdfParams.TESTING)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `different cost parameters derive a different key`() {
        val first = Kdf.deriveKey(Secret("correct-horse"), salt, KdfParams.TESTING)
        val second = Kdf.deriveKey(Secret("correct-horse"), salt, KdfParams.TESTING.copy(iterations = 2))

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `salts are random and the documented length`() {
        assertEquals(SALT_LENGTH, Kdf.newSalt().size)
        assertFalse(Kdf.newSalt().contentEquals(Kdf.newSalt()))
    }

    @Test
    fun `the default cost is the one the README documents`() {
        assertEquals(3, KdfParams.DEFAULT.iterations)
        assertEquals(64 * 1024, KdfParams.DEFAULT.memoryKib)
        assertEquals(KEY_LENGTH, KdfParams.DEFAULT.keyLength)
    }

    @Test
    fun `parameters survive an encode and decode`() {
        assertEquals(KdfParams.DEFAULT, KdfParams.decode(KdfParams.DEFAULT.encode()))
        assertEquals(KdfParams.TESTING, KdfParams.decode(KdfParams.TESTING.encode()))
    }

    @Test
    fun `the encoded form is self-describing`() {
        assertEquals(
            "argon2id v=1 t=3 m=65536 p=4 len=32",
            String(KdfParams.DEFAULT.encode()),
        )
    }

    @Test
    fun `unreadable or incomplete parameters are refused`() {
        assertThrows<UnsupportedKdfException> { KdfParams.decode("nonsense".toByteArray()) }
        assertThrows<UnsupportedKdfException> { KdfParams.decode("argon2id v=1 t=3".toByteArray()) }
        assertThrows<UnsupportedKdfException> { KdfParams.decode("argon2id v=1 t=x m=1 p=1 len=32".toByteArray()) }
        assertThrows<UnsupportedKdfException> { KdfParams.decode("scrypt v=1 t=3 m=65536 p=4 len=32".toByteArray()) }
    }

    @Test
    fun `dangerously weak parameters are refused even if well-formed`() {
        assertThrows<UnsupportedKdfException> { KdfParams(iterations = 0).validate() }
        assertThrows<UnsupportedKdfException> { KdfParams(memoryKib = 1024).validate() }
        assertThrows<UnsupportedKdfException> { KdfParams(parallelism = 0).validate() }
        assertThrows<UnsupportedKdfException> { KdfParams(keyLength = 16).validate() }
        assertThrows<UnsupportedKdfException> { KdfParams(version = 2).validate() }
    }

    @Test
    fun `a salt shorter than the standard is refused`() {
        assertThrows<IllegalArgumentException> {
            Kdf.deriveKey(Secret("correct-horse"), ByteArray(4), KdfParams.TESTING)
        }
    }

    @Test
    fun `wiping overwrites a derived key in place`() {
        val key = Kdf.deriveKey(Secret("correct-horse"), salt, KdfParams.TESTING)

        key.wipe()

        assertContentEquals(ByteArray(KEY_LENGTH), key)
    }
}
