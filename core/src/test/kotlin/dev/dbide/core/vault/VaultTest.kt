package dev.dbide.core.vault

import dev.dbide.core.connections.Secret
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultTest {
    /** A metadata store with no SQLite behind it: the vault's contract is the map. */
    private class FakeMetadataStore : MetadataStore {
        val values = mutableMapOf<String, ByteArray>()

        override suspend fun getMetadata(key: String): ByteArray? = values[key]

        override suspend fun putMetadata(key: String, value: ByteArray) {
            values[key] = value
        }
    }

    private val store = FakeMetadataStore()
    private var now = Instant.parse("2026-08-20T10:00:00Z")

    // Argon2id at production cost would make this suite take minutes.
    private fun vault() = Vault(store, params = KdfParams.TESTING, clock = { now })

    private val password get() = Secret("correct-horse")

    @Test
    fun `a fresh installation needs setup`() = runTest {
        assertEquals(VaultState.SETUP_REQUIRED, vault().state())
    }

    @Test
    fun `setup leaves the vault unlocked and stores only salt, parameters, and verifier`() = runTest {
        val vault = vault()

        vault.setUp(password)

        assertEquals(VaultState.UNLOCKED, vault.state())
        assertTrue(vault.isUnlocked)
        assertEquals(
            setOf(Vault.META_SALT, Vault.META_PARAMS, Vault.META_VERIFIER),
            store.values.keys,
        )
    }

    @Test
    fun `nothing stored resembles the master password`() = runTest {
        vault().setUp(password)

        store.values.forEach { (key, value) ->
            assertFalse(
                String(value, Charsets.ISO_8859_1).contains("correct-horse"),
                "the master password leaked into $key",
            )
        }
    }

    @Test
    fun `the stored salt is the documented length and the parameters are readable`() = runTest {
        vault().setUp(password)

        assertEquals(SALT_LENGTH, store.values.getValue(Vault.META_SALT).size)
        assertEquals(KdfParams.TESTING, KdfParams.decode(store.values.getValue(Vault.META_PARAMS)))
    }

    @Test
    fun `a short master password is refused and nothing is written`() = runTest {
        assertThrows<WeakPasswordException> { vault().setUp(Secret("short")) }

        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `a second setup is refused`() = runTest {
        val vault = vault()
        vault.setUp(password)

        assertThrows<AlreadySetUpException> { vault.setUp(Secret("another-password")) }
    }

    @Test
    fun `a new process finds the vault locked and opens it with the same password`() = runTest {
        vault().setUp(password)

        val restarted = vault()
        assertEquals(VaultState.LOCKED, restarted.state())

        restarted.unlock(password)

        assertEquals(VaultState.UNLOCKED, restarted.state())
    }

    @Test
    fun `a wrong master password does not unlock and says only that`() = runTest {
        vault().setUp(password)
        val restarted = vault()

        val failure = assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }

        assertEquals("wrong_password", failure.code)
        assertFalse(restarted.isUnlocked)
    }

    @Test
    fun `unlocking before setup reports that, rather than a wrong password`() = runTest {
        assertThrows<NotSetUpException> { vault().unlock(password) }
    }

    @Test
    fun `a secret sealed before a restart opens after the unlock`() = runTest {
        val identity = SecretIdentity("id-1", "postgres")
        val first = vault()
        first.setUp(password)
        val envelope = first.seal(identity, Secret("hunter2"))

        val restarted = vault()
        restarted.unlock(password)

        assertEquals("hunter2", restarted.open(identity, envelope).expose())
    }

    @Test
    fun `sealing and opening are refused while locked`() = runTest {
        val identity = SecretIdentity("id-1", "postgres")
        val first = vault()
        first.setUp(password)
        val envelope = first.seal(identity, Secret("hunter2"))

        val locked = vault()

        assertThrows<VaultLockedException> { locked.seal(identity, Secret("hunter2")) }
        assertThrows<VaultLockedException> { locked.open(identity, envelope) }
    }

    @Test
    fun `locking discards the key and the vault will not seal again`() = runTest {
        val vault = vault()
        vault.setUp(password)

        vault.lock()

        assertFalse(vault.isUnlocked)
        assertEquals(VaultState.LOCKED, vault.state())
        assertThrows<VaultLockedException> { vault.seal(SecretIdentity("id-1", "postgres"), Secret("x")) }
    }

    @Test
    fun `repeated failures enter a cooldown that refuses even the right password`() = runTest {
        vault().setUp(password)
        val restarted = vault()

        repeat(Vault.MAX_FAILURES_BEFORE_COOLDOWN) {
            assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        }

        val cooldown = assertThrows<TooManyAttemptsException> { restarted.unlock(password) }
        assertTrue(cooldown.retryAfter > kotlin.time.Duration.ZERO)
        assertFalse(restarted.isUnlocked)
    }

    @Test
    fun `the cooldown widens with each further failure`() = runTest {
        vault().setUp(password)
        val restarted = vault()

        repeat(Vault.MAX_FAILURES_BEFORE_COOLDOWN) {
            assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        }
        val first = assertThrows<TooManyAttemptsException> { restarted.unlock(password) }.retryAfter

        // Wait the first cooldown out, then fail once more.
        now = now.plusMillis(first.inWholeMilliseconds + 1)
        assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        val second = assertThrows<TooManyAttemptsException> { restarted.unlock(password) }.retryAfter

        assertTrue(second > first, "expected $second to be longer than $first")
    }

    @Test
    fun `the cooldown expires and the right password then works`() = runTest {
        vault().setUp(password)
        val restarted = vault()
        repeat(Vault.MAX_FAILURES_BEFORE_COOLDOWN) {
            assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        }
        val wait = assertThrows<TooManyAttemptsException> { restarted.unlock(password) }.retryAfter

        now = now.plusMillis(wait.inWholeMilliseconds + 1)
        restarted.unlock(password)

        assertTrue(restarted.isUnlocked)
    }

    @Test
    fun `a successful unlock clears the failure count`() = runTest {
        vault().setUp(password)
        val restarted = vault()
        repeat(Vault.MAX_FAILURES_BEFORE_COOLDOWN - 1) {
            assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        }

        restarted.unlock(password)
        assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }

        // One failure after a success must not resume the old count and trip the cooldown.
        assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
    }

    @Test
    fun `a configuration file written with unusable parameters refuses to open`() = runTest {
        vault().setUp(password)
        store.values[Vault.META_PARAMS] = "argon2id v=99 t=3 m=65536 p=4 len=32".toByteArray()

        assertThrows<UnsupportedKdfException> { vault().unlock(password) }
    }

    @Test
    fun `a half-written setup leaves the vault needing setup rather than unopenable`() = runTest {
        vault().setUp(password)
        // The verifier is written last, so its absence is what a crash mid-setup looks like.
        store.values.remove(Vault.META_VERIFIER)

        assertEquals(VaultState.SETUP_REQUIRED, vault().state())
        assertNotNull(store.values[Vault.META_SALT])
        assertNull(store.values[Vault.META_VERIFIER])
    }
}
