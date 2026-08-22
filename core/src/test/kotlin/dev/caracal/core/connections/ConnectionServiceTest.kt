package dev.caracal.core.connections

import dev.caracal.core.history.HistoryScope
import dev.caracal.core.registry.ConnectionRegistry
import dev.caracal.core.store.ConfigStore
import dev.caracal.core.store.ConnectionNotFoundException
import dev.caracal.core.store.DuplicateNameException
import dev.caracal.core.vault.KdfParams
import dev.caracal.core.vault.SecretIdentity
import dev.caracal.core.vault.Vault
import dev.caracal.core.vault.VaultDamagedException
import dev.caracal.core.vault.VaultLockedException
import dev.caracal.core.vault.VaultState
import dev.caracal.core.vault.WrongPasswordException
import dev.caracal.engine.api.FormKeys
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.redis.RedisEngine
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * The whole M1 stack over a real SQLite file: store, vault, and registry joined by
 * the service. Nothing here dials a database, so it runs headlessly and offline.
 */
class ConnectionServiceTest {
    @TempDir
    lateinit var directory: Path

    private val master = "correct-horse-battery"
    private val createdAt = Instant.parse("2026-08-20T10:00:00Z")

    /** One process's worth of collaborators. Building a second models a restart. */
    private class Session(
        val store: ConfigStore,
        val vault: Vault,
        val registry: ConnectionRegistry,
        val service: ConnectionService,
    ) : AutoCloseable {
        override fun close() = store.close()
    }

    private suspend fun session(): Session {
        val store = ConfigStore.open(directory.resolve("caracal.db"))
        // Argon2id at production cost would make this suite take minutes.
        val vault = Vault(store, params = KdfParams.TESTING)
        val registry = ConnectionRegistry()
        var counter = 0
        return Session(
            store,
            vault,
            registry,
            DefaultConnectionService(
                store = store,
                vault = vault,
                registry = registry,
                clock = { createdAt },
                newId = { ConnectionId("id-${++counter}") },
            ),
        )
    }

    /** An unlocked session with a master password already chosen. */
    private suspend fun unlocked(): Session = session().also { it.service.setUp(Secret(master)) }

    private fun postgresDraft(
        name: String = "Local",
        secret: SecretUpdate = SecretUpdate.Replace(Secret("hunter2")),
    ) = networkDraft(
        engineId = PostgresEngine.ID,
        name = name,
        database = "caracal",
        username = "caracal",
        secret = secret,
    )

    // --- Vault damage --------------------------------------------------------

    @Test
    fun `setting up over a lost verifier is refused while sealed secrets exist`() = runTest {
        // The salt is what every sealed credential was sealed under, and this is the
        // ordinary first-run screen. Writing a new salt here would make every stored
        // password permanently unopenable, silently, and look exactly like a fresh
        // installation to the person doing it.
        unlocked().use { session -> session.service.create(postgresDraft()) }
        removeVerifier()

        session().use { session ->
            val salt = assertNotNull(session.store.getMetadata(Vault.META_SALT))
            assertThrows<VaultDamagedException> {
                runBlocking { session.service.setUp(Secret("a-new-password")) }
            }
            assertContentEquals(salt, session.store.getMetadata(Vault.META_SALT))
        }
    }

    @Test
    fun `an interrupted first run can still choose a password, because nothing was sealed`() = runTest {
        // The other half of the same state. A crash between the salt and the verifier
        // leaves no sealed secret behind, so re-keying costs nothing and the user gets
        // the first-run screen they expected rather than an error about a file that
        // has nothing in it.
        unlocked().use { session -> assertTrue(session.service.list().isEmpty()) }
        removeVerifier()

        session().use { session ->
            session.service.setUp(Secret("a-new-password"))
            assertEquals(VaultState.UNLOCKED, session.service.vaultState())
        }
    }

    /** What a lost verifier row looks like on disk. The store has no delete for it. */
    private fun removeVerifier() {
        val path = directory.resolve("caracal.db")
        DriverManager.getConnection("jdbc:sqlite:" + path).use { connection ->
            connection.prepareStatement("DELETE FROM app_metadata WHERE key = ?").use { statement ->
                statement.setString(1, Vault.META_VERIFIER)
                statement.executeUpdate()
            }
        }
    }

    // --- Locked state --------------------------------------------------------

    @Test
    fun `every connection operation is refused while locked`() = runTest {
        session().use { session ->
            session.service.setUp(Secret(master))
            val view = session.service.create(postgresDraft())
            session.vault.lock()

            assertThrows<VaultLockedException> { session.service.list() }
            assertThrows<VaultLockedException> { session.service.get(view.id) }
            assertThrows<VaultLockedException> { session.service.create(postgresDraft("Other")) }
            assertThrows<VaultLockedException> { session.service.update(view.id, postgresDraft()) }
            assertThrows<VaultLockedException> { session.service.delete(view.id) }
            assertThrows<VaultLockedException> { session.service.test(view.id) }
            assertThrows<VaultLockedException> { session.service.open(view.id) }
            assertThrows<VaultLockedException> { session.service.close(view.id) }
            // History is the newest of these and the easiest to leave out, and it is
            // the one holding the statements: a locked application must not read back
            // the `WHERE email = '…'` someone ran this morning.
            assertThrows<VaultLockedException> { session.service.history() }
            assertThrows<VaultLockedException> { session.service.clearHistory(HistoryScope.Everything) }
        }
    }

    @Test
    fun `listing is refused while locked, not merely reading secrets`() = runTest {
        session().use { session ->
            session.service.setUp(Secret(master))
            session.service.create(postgresDraft())
            session.service.lock()

            // Summaries carry no secrets, but they are still a map of where this
            // user's databases live.
            assertThrows<VaultLockedException> { session.service.list() }
        }
    }

    @Test
    fun `locking closes every live client, so a locked application is not still connected`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())

            session.service.lock()

            assertFalse(session.vault.isUnlocked)
            assertEquals(emptyMap(), session.registry.states().filterValues { it.isOpen })
            assertEquals(RuntimeStatus.CLOSED, session.registry.state(view.id).status)
        }
    }

    // --- Create --------------------------------------------------------------

    @Test
    fun `a created connection comes back as a summary with no secret in it`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())

            assertEquals("Local", view.config.name)
            assertEquals(PostgresEngine.ID, view.config.engineId)
            assertEquals(5432, view.config.port)
            assertTrue(view.hasSecret)
            assertEquals(RuntimeStatus.CLOSED, view.runtime.status)
            assertFalse(view.toString().contains("hunter2"))
        }
    }

    @Test
    fun `an engine default is applied on the way in`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(
                networkDraft(engineId = RedisEngine.ID, name = "Cache"),
            )

            assertEquals(6379, view.config.port)
            assertEquals("0", view.config.database)
            assertEquals(TlsMode.DISABLE, view.config.tlsMode)
            assertEquals(Environment.DEV, view.config.environment)
        }
    }

    @Test
    fun `a connection saved with no password stores no sealed bytes`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft(secret = SecretUpdate.Unchanged))

            assertFalse(view.hasSecret)
            assertNull(session.store.get(view.id).sealedSecret)
        }
    }

    @Test
    fun `an invalid draft is refused with the offending fields named`() = runTest {
        unlocked().use { session ->
            val failure = assertThrows<ValidationException> {
                session.service.create(networkDraft(engineId = PostgresEngine.ID, name = "", host = ""))
            }

            assertEquals(
                listOf(ValidationError.NAME, FormKeys.HOST, FormKeys.DATABASE, FormKeys.USER),
                failure.errors.map { it.field },
            )
        }
    }

    @Test
    fun `a duplicate name is refused`() = runTest {
        unlocked().use { session ->
            session.service.create(postgresDraft(name = "Local"))

            assertThrows<DuplicateNameException> { session.service.create(postgresDraft(name = "Local")) }
        }
    }

    @Test
    fun `the stored secret is sealed, not stored as text`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())

            val sealed = assertNotNull(session.store.get(view.id).sealedSecret)
            assertFalse(String(sealed, Charsets.ISO_8859_1).contains("hunter2"))
            assertEquals(
                "hunter2",
                session.vault.open(SecretIdentity.of(view.config), sealed).expose(),
            )
        }
    }

    // --- Update --------------------------------------------------------------

    @Test
    fun `an edit that does not mention the secret leaves it exactly as it was`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())
            val before = assertNotNull(session.store.get(view.id).sealedSecret)

            val updated = session.service.update(
                view.id,
                ConnectionDraft.of(view.config).copy(color = "#ff8800"),
            )

            assertTrue(updated.hasSecret)
            assertEquals("#ff8800", updated.config.color)
            assertContentEquals(before, session.store.get(view.id).sealedSecret)
        }
    }

    @Test
    fun `an edit can deliberately clear the secret`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())

            val updated = session.service.update(
                view.id,
                ConnectionDraft.of(view.config).copy(secret = SecretUpdate.Clear),
            )

            assertFalse(updated.hasSecret)
            assertNull(session.store.get(view.id).sealedSecret)
        }
    }

    @Test
    fun `an edit can replace the secret`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())
            val before = assertNotNull(session.store.get(view.id).sealedSecret)

            session.service.update(
                view.id,
                ConnectionDraft.of(view.config).copy(secret = SecretUpdate.Replace(Secret("new-password"))),
            )

            val after = assertNotNull(session.store.get(view.id).sealedSecret)
            assertFalse(before.contentEquals(after))
            assertEquals(
                "new-password",
                session.vault.open(SecretIdentity.of(view.config), after).expose(),
            )
        }
    }

    @Test
    fun `replacing the secret with an empty one clears it rather than storing nothing useful`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())

            val updated = session.service.update(
                view.id,
                ConnectionDraft.of(view.config).copy(secret = SecretUpdate.Replace(Secret(""))),
            )

            assertFalse(updated.hasSecret)
        }
    }

    @Test
    fun `changing the engine reseals the secret so it stays bound to its record`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())

            val updated = session.service.update(
                view.id,
                // A PostgreSQL database name is not a Redis index, so the switch
                // carries the fields the form would have reset. What is being
                // asserted is the resealing, not the form's carry-over rules.
                ConnectionDraft.of(view.config).let {
                    it.copy(engineId = RedisEngine.ID, values = it.values + (FormKeys.DATABASE to "0"))
                },
            )

            // The engine is authenticated alongside the ciphertext, so an unresealed
            // secret would no longer open.
            val sealed = assertNotNull(session.store.get(view.id).sealedSecret)
            assertEquals(
                "hunter2",
                session.vault.open(SecretIdentity.of(updated.config), sealed).expose(),
            )
        }
    }

    @Test
    fun `an edit cannot rewrite the creation time`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())

            val updated = session.service.update(view.id, ConnectionDraft.of(view.config).copy(name = "Renamed"))

            assertEquals(createdAt, updated.config.createdAt)
            assertEquals(view.id, updated.id)
        }
    }

    @Test
    fun `an invalid edit is refused before anything is written`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())

            assertThrows<ValidationException> {
                session.service.update(view.id, ConnectionDraft.of(view.config).let { it.copy(values = it.values + (FormKeys.HOST to "")) })
            }

            assertEquals("localhost", session.service.get(view.id).config.host)
        }
    }

    // --- Delete --------------------------------------------------------------

    @Test
    fun `deleting removes the connection and its runtime entry`() = runTest {
        unlocked().use { session ->
            val view = session.service.create(postgresDraft())

            session.service.delete(view.id)

            assertEquals(emptyList(), session.service.list())
            assertEquals(emptyMap(), session.registry.states())
            assertThrows<ConnectionNotFoundException> { session.service.get(view.id) }
        }
    }

    @Test
    fun `deleting a connection that is already gone reports that`() = runTest {
        unlocked().use { session ->
            assertThrows<ConnectionNotFoundException> { session.service.delete(ConnectionId("ghost")) }
        }
    }

    // --- Listing and restart -------------------------------------------------

    @Test
    fun `the list puts production first and never carries a secret`() = runTest {
        unlocked().use { session ->
            session.service.create(postgresDraft(name = "dev-db"))
            session.service.create(
                postgresDraft(name = "prod-db").copy(environment = Environment.PROD, readOnly = true),
            )

            val views = session.service.list()

            assertEquals(listOf("prod-db", "dev-db"), views.map { it.config.name })
            assertTrue(views.first().config.readOnly)
            assertFalse(views.toString().contains("hunter2"))
        }
    }

    @Test
    fun `connections survive a restart and come back closed rather than statusless`() = runTest {
        val id = unlocked().use { session ->
            session.service.create(postgresDraft()).id
        }

        session().use { restarted ->
            assertEquals(VaultState.LOCKED, restarted.service.vaultState())
            restarted.service.unlock(Secret(master))

            val views = restarted.service.list()
            assertEquals(1, views.size)
            assertEquals(id, views.single().id)
            assertTrue(views.single().hasSecret)
            // The registry has never seen this connection in this process.
            assertEquals(RuntimeStatus.CLOSED, views.single().runtime.status)
        }
    }

    @Test
    fun `a saved password survives a restart and still opens`() = runTest {
        val id = unlocked().use { it.service.create(postgresDraft()).id }

        session().use { restarted ->
            restarted.service.unlock(Secret(master))

            val record = restarted.store.get(id)
            assertEquals(
                "hunter2",
                restarted.vault.open(SecretIdentity.of(record.config), record.sealedSecret!!).expose(),
            )
        }
    }

    @Test
    fun `a wrong master password leaves every connection unusable`() = runTest {
        val id = unlocked().use { it.service.create(postgresDraft()).id }

        session().use { restarted ->
            assertThrows<WrongPasswordException> { restarted.service.unlock(Secret("not-the-password")) }

            assertThrows<VaultLockedException> { restarted.service.list() }
            assertThrows<VaultLockedException> { restarted.service.get(id) }
        }
    }
}
