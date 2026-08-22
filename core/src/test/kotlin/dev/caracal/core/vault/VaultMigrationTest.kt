package dev.caracal.core.vault

import dev.caracal.core.connections.Secret
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What unlocking does to records written by an older build.
 *
 * The fixture test proves it against the file v0.1.0 actually wrote; this proves the
 * properties that make an interrupted run survivable, which a single well-behaved
 * file cannot show.
 */
class VaultMigrationTest {
    private val store = FakeVaultStore()
    private val masterPassword get() = Secret("correct-horse")

    // Argon2id at production cost would make this suite take minutes.
    private fun vault() = Vault(store, store, params = KdfParams.TESTING)

    @Test
    fun `every legacy record is rewritten, and reads the same afterwards`() = runTest {
        val expected = seedLegacy(count = 5)

        vault().unlock(masterPassword)

        assertEquals(5, store.replacements)
        store.sealed.forEach { (identity, envelope) ->
            assertEquals(RECORD_VERSION, open(identity, envelope).schemaVersion)
            assertEquals(expected.getValue(identity), open(identity, envelope).passwordText())
        }
    }

    @Test
    fun `a second unlock rewrites nothing`() = runTest {
        seedLegacy(count = 5)
        vault().unlock(masterPassword)
        val afterFirst = store.sealed.mapValues { it.value.copyOf() }

        vault().unlock(masterPassword)

        assertEquals(5, store.replacements)
        afterFirst.forEach { (identity, envelope) ->
            assertContentEquals(envelope, store.sealed.getValue(identity), "record $identity was rewritten again")
        }
    }

    @Test
    fun `a process that dies mid-migration leaves a vault that still unlocks`() = runTest {
        val expected = seedLegacy(count = 5)
        val before = store.sealed.mapValues { it.value.copyOf() }
        store.failReplaceAfter = 2

        val vault = vault()
        vault.unlock(masterPassword)

        // The unlock itself is the acceptance criterion: a migration that could not
        // finish must not be the reason the user cannot open their own vault.
        assertTrue(vault.isUnlocked)
        assertEquals(2, store.replacements)

        // Two rows moved, three did not, and every one of the five still opens —
        // which is the property that makes the halfway state a state and not damage.
        val moved = store.sealed.count { (identity, envelope) -> !envelope.contentEquals(before.getValue(identity)) }
        assertEquals(2, moved)
        store.sealed.forEach { (identity, envelope) ->
            assertEquals(expected.getValue(identity), open(identity, envelope).passwordText())
        }
        assertEquals(
            setOf(RECORD_VERSION_LEGACY, RECORD_VERSION),
            store.sealed.map { (identity, envelope) -> open(identity, envelope).schemaVersion }.toSet(),
        )
    }

    @Test
    fun `the next unlock finishes what the interrupted one started`() = runTest {
        seedLegacy(count = 5)
        store.failReplaceAfter = 2
        vault().unlock(masterPassword)

        store.failReplaceAfter = null
        vault().unlock(masterPassword)

        assertEquals(5, store.replacements)
        store.sealed.forEach { (identity, envelope) ->
            assertEquals(RECORD_VERSION, open(identity, envelope).schemaVersion)
        }
    }

    @Test
    fun `a record that will not open stops neither the unlock nor the other records`() = runTest {
        seedLegacy(count = 3)
        // A credential sealed under some other key, or damaged in place. It is
        // already lost to its own connection; it must not take the vault with it.
        val damaged = SecretIdentity("damaged", "postgres")
        store.sealed[damaged] = byteArrayOf(ENVELOPE_VERSION) + ByteArray(NONCE_LENGTH + 16) { 7 }

        val vault = vault()
        vault.unlock(masterPassword)

        assertTrue(vault.isUnlocked)
        assertEquals(3, store.replacements)
        // Left exactly as it was, rather than deleted or replaced with something
        // openable — the bytes are the only chance of ever recovering it.
        assertContentEquals(
            byteArrayOf(ENVELOPE_VERSION) + ByteArray(NONCE_LENGTH + 16) { 7 },
            store.sealed.getValue(damaged),
        )
    }

    @Test
    fun `a store that cannot be read at all does not keep the user out`() = runTest {
        val unreadable = object : SealedSecretStore {
            override suspend fun sealedSecrets(): List<SealedSecret> = throw IllegalStateException("no store")

            override suspend fun replaceSealedSecret(identity: SecretIdentity, envelope: ByteArray) = Unit
        }
        Vault(store, unreadable, params = KdfParams.TESTING).setUp(masterPassword)

        val vault = Vault(store, unreadable, params = KdfParams.TESTING)
        vault.unlock(masterPassword)

        assertTrue(vault.isUnlocked)
    }

    @Test
    fun `a wrong password migrates nothing`() = runTest {
        seedLegacy(count = 3)

        assertFalse(runCatching { vault().unlock(Secret("not-the-password")) }.isSuccess)

        assertEquals(0, store.replacements)
    }

    /**
     * Writes [count] records in the v1 format — a version byte and the password's
     * UTF-8 — sealed under the real master key, and returns what each should read as.
     */
    private suspend fun seedLegacy(count: Int): Map<SecretIdentity, String> {
        vault().setUp(masterPassword)
        val key = masterKey()
        return buildMap {
            repeat(count) { index ->
                val identity = SecretIdentity("id-$index", "postgres")
                val plaintext = "hunter$index"
                store.sealed[identity] = legacyEnvelope(key, identity, plaintext)
                put(identity, plaintext)
            }
        }
    }

    /**
     * A v1 envelope, in the format the released build wrote: a version byte, then the
     * password's UTF-8, encrypted by the same [Seal] a released build used.
     *
     * [Seal.seal] cannot produce one — it writes the current version, as it should —
     * so only the plaintext is assembled here. Hand-rolling the AES-GCM as well would
     * prove that the test agrees with itself and nothing more.
     */
    private fun legacyEnvelope(key: ByteArray, identity: SecretIdentity, password: String): ByteArray =
        Seal.sealRaw(key, identity, byteArrayOf(RECORD_VERSION_LEGACY.toByte()) + password.toByteArray(Charsets.UTF_8))

    private fun masterKey(): ByteArray = Kdf.deriveKey(
        masterPassword,
        store.values.getValue(Vault.META_SALT),
        KdfParams.decode(store.values.getValue(Vault.META_PARAMS)),
    )

    private fun open(identity: SecretIdentity, envelope: ByteArray): VaultRecord =
        Seal.open(masterKey(), identity, envelope)
}
