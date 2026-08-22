package dev.caracal.core.vault

import dev.caracal.core.connections.Secret
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

    private val masterPassword get() = Secret("correct-horse")

    @Test
    fun `setup refuses to re-key a vault whose verifier is missing but whose salt is not`() = runTest {
        // The salt is what every stored secret was sealed under. Writing a new one
        // over it makes them unopenable by any password, for good — and the screen
        // that would do it is the ordinary first-run one, so the user would never
        // know that was the choice they were making.
        vault().setUp(masterPassword)
        val salt = store.values[Vault.META_SALT]!!
        store.values.remove(Vault.META_VERIFIER)

        assertThrows<VaultDamagedException> { runBlocking { vault().setUp(Secret("another-password")) } }
        assertEquals(salt.toList(), store.values[Vault.META_SALT]!!.toList())
    }

    @Test
    fun `a corrupted verifier is reported as damage, not as a wrong password`() = runTest {
        val vault = vault()
        vault.setUp(masterPassword)
        vault.lock()
        // Ten bytes: too short to be an envelope at all. A partial write, a recovered
        // WAL, a bad restore.
        store.values[Vault.META_VERIFIER] = ByteArray(10) { 7 }

        assertThrows<MalformedEnvelopeException> { runBlocking { vault.unlock(masterPassword) } }
    }

    @Test
    fun `a corrupted verifier does not spend the unlock attempt budget`() = runTest {
        val vault = vault()
        vault.setUp(masterPassword)
        vault.lock()
        val verifier = store.values[Vault.META_VERIFIER]!!
        store.values[Vault.META_VERIFIER] = ByteArray(10) { 7 }

        // Five attempts is the cooldown threshold. Counting these would lock a user
        // out of a vault their correct password still opens.
        repeat(6) { assertThrows<MalformedEnvelopeException> { runBlocking { vault.unlock(masterPassword) } } }

        store.values[Vault.META_VERIFIER] = verifier
        vault.unlock(masterPassword)
        assertTrue(vault.isUnlocked)
    }

    @Test
    fun `concurrent setup calls agree on one salt and verifier`() = runTest(timeout = 60.seconds) {
        val vault = vault()
        val first = async(Dispatchers.Default) { runCatching { vault.setUp(Secret("first-password")) } }
        val second = async(Dispatchers.Default) { runCatching { vault.setUp(Secret("second-password")) } }
        first.await()
        second.await()

        // Whichever won, the pair on disk is that one's. A salt from one call beside a
        // verifier from the other is a vault no password opens.
        vault.lock()
        val opens = listOf("first-password", "second-password").count { candidate ->
            runCatching { vault.unlock(Secret(candidate)) }.isSuccess
        }
        assertEquals(1, opens)
    }

    @Test
    fun `a fresh installation needs setup`() = runTest {
        assertEquals(VaultState.SETUP_REQUIRED, vault().state())
    }

    @Test
    fun `setup leaves the vault unlocked and stores only salt, parameters, and verifier`() = runTest {
        val vault = vault()

        vault.setUp(masterPassword)

        assertEquals(VaultState.UNLOCKED, vault.state())
        assertTrue(vault.isUnlocked)
        assertEquals(
            setOf(Vault.META_SALT, Vault.META_PARAMS, Vault.META_VERIFIER),
            store.values.keys,
        )
    }

    @Test
    fun `nothing stored resembles the master password`() = runTest {
        vault().setUp(masterPassword)

        store.values.forEach { (key, value) ->
            assertFalse(
                String(value, Charsets.ISO_8859_1).contains("correct-horse"),
                "the master password leaked into $key",
            )
        }
    }

    @Test
    fun `the stored salt is the documented length and the parameters are readable`() = runTest {
        vault().setUp(masterPassword)

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
        vault.setUp(masterPassword)

        assertThrows<AlreadySetUpException> { vault.setUp(Secret("another-password")) }
    }

    @Test
    fun `a new process finds the vault locked and opens it with the same password`() = runTest {
        vault().setUp(masterPassword)

        val restarted = vault()
        assertEquals(VaultState.LOCKED, restarted.state())

        restarted.unlock(masterPassword)

        assertEquals(VaultState.UNLOCKED, restarted.state())
    }

    @Test
    fun `a wrong master password does not unlock and says only that`() = runTest {
        vault().setUp(masterPassword)
        val restarted = vault()

        val failure = assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }

        assertEquals("wrong_password", failure.code)
        assertFalse(restarted.isUnlocked)
    }

    @Test
    fun `unlocking before setup reports that, rather than a wrong password`() = runTest {
        assertThrows<NotSetUpException> { vault().unlock(masterPassword) }
    }

    @Test
    fun `a secret sealed before a restart opens after the unlock`() = runTest {
        val identity = SecretIdentity("id-1", "postgres")
        val first = vault()
        first.setUp(masterPassword)
        val envelope = first.seal(identity, password("hunter2"))

        val restarted = vault()
        restarted.unlock(masterPassword)

        assertEquals("hunter2", restarted.open(identity, envelope).passwordText())
    }

    @Test
    fun `sealing and opening are refused while locked`() = runTest {
        val identity = SecretIdentity("id-1", "postgres")
        val first = vault()
        first.setUp(masterPassword)
        val envelope = first.seal(identity, password("hunter2"))

        val locked = vault()

        assertThrows<VaultLockedException> { locked.seal(identity, password("hunter2")) }
        assertThrows<VaultLockedException> { locked.open(identity, envelope) }
    }

    /**
     * A regression, and the only test here that needs real threads.
     *
     * `seal` used to take the key out of the mutex and run AES outside it, so a
     * concurrent `lock` wiped the array mid-cipher. The seal still returned an
     * envelope, but one encrypted under a key of zeroes: the saved credential was
     * lost, and what remained on disk was readable by anyone with the file. Revert
     * the lock in `withKey` and this fails on roughly every attempt.
     *
     * Whichever operation wins, only two outcomes are permitted: the seal was
     * refused because the vault locked first, or it produced an envelope that the
     * same master password still opens.
     */
    @Test
    fun `a seal racing a lock is refused or produces an openable envelope`() =
        runBlocking(Dispatchers.Default) {
            val vault = vault()
            vault.setUp(masterPassword)
            val identity = SecretIdentity("id-1", "postgres")

            repeat(RACE_ATTEMPTS) { attempt ->
                if (!vault.isUnlocked) vault.unlock(masterPassword)

                val sealing = async { runCatching { vault.seal(identity, password("hunter2")) } }
                val locking = async { vault.lock() }
                locking.await()

                sealing.await()
                    .onSuccess { envelope ->
                        vault.unlock(masterPassword)
                        assertEquals(
                            "hunter2",
                            vault.open(identity, envelope).passwordText(),
                            "attempt $attempt sealed an envelope its own master password cannot open",
                        )
                    }
                    .onFailure { failure ->
                        assertIs<VaultLockedException>(
                            failure,
                            "attempt $attempt failed for a reason other than the lock",
                        )
                    }
            }
        }

    @Test
    fun `locking discards the key and the vault will not seal again`() = runTest {
        val vault = vault()
        vault.setUp(masterPassword)

        vault.lock()

        assertFalse(vault.isUnlocked)
        assertEquals(VaultState.LOCKED, vault.state())
        assertThrows<VaultLockedException> { vault.seal(SecretIdentity("id-1", "postgres"), password("x")) }
    }

    @Test
    fun `repeated failures enter a cooldown that refuses even the right password`() = runTest {
        vault().setUp(masterPassword)
        val restarted = vault()

        repeat(Vault.MAX_FAILURES_BEFORE_COOLDOWN) {
            assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        }

        val cooldown = assertThrows<TooManyAttemptsException> { restarted.unlock(masterPassword) }
        assertTrue(cooldown.retryAfter > kotlin.time.Duration.ZERO)
        assertFalse(restarted.isUnlocked)
    }

    @Test
    fun `the cooldown widens with each further failure`() = runTest {
        vault().setUp(masterPassword)
        val restarted = vault()

        repeat(Vault.MAX_FAILURES_BEFORE_COOLDOWN) {
            assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        }
        val first = assertThrows<TooManyAttemptsException> { restarted.unlock(masterPassword) }.retryAfter

        // Wait the first cooldown out, then fail once more.
        now = now.plusMillis(first.inWholeMilliseconds + 1)
        assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        val second = assertThrows<TooManyAttemptsException> { restarted.unlock(masterPassword) }.retryAfter

        assertTrue(second > first, "expected $second to be longer than $first")
    }

    @Test
    fun `the cooldown expires and the right password then works`() = runTest {
        vault().setUp(masterPassword)
        val restarted = vault()
        repeat(Vault.MAX_FAILURES_BEFORE_COOLDOWN) {
            assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        }
        val wait = assertThrows<TooManyAttemptsException> { restarted.unlock(masterPassword) }.retryAfter

        now = now.plusMillis(wait.inWholeMilliseconds + 1)
        restarted.unlock(masterPassword)

        assertTrue(restarted.isUnlocked)
    }

    @Test
    fun `a successful unlock clears the failure count`() = runTest {
        vault().setUp(masterPassword)
        val restarted = vault()
        repeat(Vault.MAX_FAILURES_BEFORE_COOLDOWN - 1) {
            assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
        }

        restarted.unlock(masterPassword)
        assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }

        // One failure after a success must not resume the old count and trip the cooldown.
        assertThrows<WrongPasswordException> { restarted.unlock(Secret("wrong-password")) }
    }

    @Test
    fun `a configuration file written with unusable parameters refuses to open`() = runTest {
        vault().setUp(masterPassword)
        store.values[Vault.META_PARAMS] = "argon2id v=99 t=3 m=65536 p=4 len=32".toByteArray()

        assertThrows<UnsupportedKdfException> { vault().unlock(masterPassword) }
    }

    @Test
    fun `a half-written setup leaves the vault needing setup rather than unopenable`() = runTest {
        vault().setUp(masterPassword)
        // The verifier is written last, so its absence is what a crash mid-setup looks like.
        store.values.remove(Vault.META_VERIFIER)

        assertEquals(VaultState.SETUP_REQUIRED, vault().state())
        assertNotNull(store.values[Vault.META_SALT])
        assertNull(store.values[Vault.META_VERIFIER])
    }

    companion object {
        /**
         * Enough attempts that the two coroutines actually overlap. The wiped-key bug
         * showed up in well over nine tenths of them, so this is generous.
         */
        private const val RACE_ATTEMPTS = 100
    }
}
