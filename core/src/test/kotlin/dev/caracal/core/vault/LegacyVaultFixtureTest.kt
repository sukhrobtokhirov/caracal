package dev.caracal.core.vault

import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Secret
import dev.caracal.core.store.ConfigStore
import dev.caracal.engine.api.FormKeys
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * A configuration file as v0.1.0 wrote one, opened by this build.
 *
 * The fixture is a real SQLite configuration database at schema 3 — the schema the
 * released version shipped, before Phase 3's step 4 — holding three connections
 * whose secrets are sealed in the v1 payload format. It is the proof that someone
 * who installed the released build can install the next one and still reach their
 * databases.
 *
 * **The fixture is never regenerated.** A fixture rewritten by the code it is meant
 * to hold to account proves only that the code agrees with itself; the whole of its
 * value is that no line of it was produced by the build under test. [DIGEST] is what
 * makes that mechanical rather than a comment: replacing the file fails here, and
 * the replacement has to be argued for in the same commit that changes the constant.
 *
 * It was written on 2026-08-22, at commit `b2f48ee`, by a throwaway generator that
 * used the then-current `Kdf`, `Seal` and the schema-1-through-3 DDL from
 * `Migrations.kt`, and was deleted rather than checked in for the reason above. The
 * master password is [PASSWORD], and Argon2id is at production cost because that is
 * what a shipped installation has.
 */
class LegacyVaultFixtureTest {

    @Test
    fun `the fixture is the file that shipped`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(fixtureBytes())
        assertEquals(
            DIGEST,
            digest.joinToString("") { "%02x".format(it) },
            "The v1 vault fixture has been rewritten. It is the only evidence that a released " +
                "installation can be upgraded, and a regenerated one is not evidence.",
        )
    }

    @Test
    fun `a v1 vault unlocks and every sealed secret opens`(@TempDir directory: Path) = runTest {
        val store = ConfigStore.open(install(directory))
        try {
            // Opening it migrated the store from the schema the release shipped.
            assertEquals(4, store.schemaVersion())

            val vault = Vault(store)
            assertEquals(VaultState.LOCKED, vault.state())
            vault.unlock(Secret(PASSWORD))

            val records = store.list().associateBy { it.config.name }
            assertEquals(setOf("shipped-postgres", "shipped-redis", "shipped-trust-auth"), records.keys)

            val postgres = records.getValue("shipped-postgres")
            // The columns migration 4 turned into engine-declared settings.
            assertEquals("app_user", postgres.config.settings[FormKeys.USER])
            assertEquals("require", postgres.config.settings[FormKeys.TLS])
            assertEquals("db.internal", postgres.config.host)
            assertEquals(
                "pg-legacy-secret",
                vault.open(SecretIdentity.of(postgres.config), assertNotNull(postgres.sealedSecret)).expose(),
            )

            val redis = records.getValue("shipped-redis")
            assertEquals(
                "redis-legacy-secret",
                vault.open(SecretIdentity.of(redis.config), assertNotNull(redis.sealedSecret)).expose(),
            )

            // A connection that never had a stored password. It has to survive as one:
            // a migration that invents an empty secret for it changes how it dials.
            assertNull(records.getValue("shipped-trust-auth").sealedSecret)
        } finally {
            store.close()
        }
    }

    @Test
    fun `a secret cannot be moved between the fixture's connections`(@TempDir directory: Path) = runTest {
        val store = ConfigStore.open(install(directory))
        try {
            val vault = Vault(store)
            vault.unlock(Secret(PASSWORD))
            val postgres = store.get(ConnectionId(POSTGRES_ID))
            // The identity is authenticated, so a v1 envelope is still bound to the
            // connection it was sealed for. That property has to survive Phase 4.
            assertThrows<SecretUnreadableException> {
                runBlocking { vault.open(SecretIdentity(REDIS_ID, "redis"), assertNotNull(postgres.sealedSecret)) }
            }
        } finally {
            store.close()
        }
    }

    /** Copies the fixture out of the classpath: opening it migrates the file in place. */
    private fun install(directory: Path): Path {
        val target = directory.resolve("caracal.db")
        Files.write(target, fixtureBytes())
        return target
    }

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(RESOURCE)) { "missing fixture $RESOURCE" }
            .use { it.readBytes() }

    private companion object {
        const val RESOURCE = "/vault/v1-legacy.db"
        const val PASSWORD = "legacy-master-password"
        const val DIGEST = "9f896611a82c0e81df425d7aec4071ef52a23fec723bc9624ccd18d8dbc2dca9"
        const val POSTGRES_ID = "6a1e6e0c-1f4e-4a0f-9d3b-0f2a1c4b5d60"
        const val REDIS_ID = "7b2f7f1d-2a5f-4b10-8e4c-1a3b2d5c6e71"
    }
}
