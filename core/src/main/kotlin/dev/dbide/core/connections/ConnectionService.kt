package dev.dbide.core.connections

import dev.dbide.core.catalog.CatalogObject
import dev.dbide.core.catalog.ColumnInfo
import dev.dbide.core.catalog.Listing
import dev.dbide.core.catalog.ObjectKind
import dev.dbide.core.catalog.SchemaInfo
import dev.dbide.core.postgres.PostgresCatalog
import dev.dbide.core.postgres.PostgresConnectionConfig
import dev.dbide.core.postgres.PostgresProbe
import dev.dbide.core.redis.RedisSession
import dev.dbide.core.registry.ConnectionRegistry
import dev.dbide.core.result.QueryResult
import dev.dbide.core.store.ConfigStore
import dev.dbide.core.vault.SecretIdentity
import dev.dbide.core.vault.Vault
import dev.dbide.core.vault.VaultException
import dev.dbide.core.vault.VaultLockedException
import dev.dbide.core.vault.VaultState
import java.time.Instant
import java.util.UUID

/**
 * Everything the UI can do to connections, as the source plan's §4 defines it.
 *
 * It is an interface so the UI can be driven against a stand-in: a Compose test
 * that clicks "Create" should not need SQLite and an Argon2id derivation behind it.
 * [DefaultConnectionService] is the one real implementation.
 */
interface ConnectionService {
    /** Whether setup is required, and whether the vault is open. */
    suspend fun vaultState(): VaultState

    /** Chooses the master password on first run. */
    suspend fun setUp(password: Secret)

    /** Opens the vault with the master password. */
    suspend fun unlock(password: Secret)

    /** Discards the master key and closes every live client. */
    suspend fun lock()

    /** Every saved connection with its runtime state. Never returns secrets. */
    suspend fun list(): List<ConnectionView>

    /** One saved connection with its runtime state. */
    suspend fun get(id: ConnectionId): ConnectionView

    /** Saves a new connection and seals its secret. */
    suspend fun create(draft: ConnectionDraft): ConnectionView

    /** Replaces a connection's fields, leaving its secret alone unless told otherwise. */
    suspend fun update(id: ConnectionId, draft: ConnectionDraft): ConnectionView

    /** Closes a connection's client and removes it. */
    suspend fun delete(id: ConnectionId)

    /** Dials, authenticates, and disconnects without touching the registry. */
    suspend fun test(id: ConnectionId): TestResult

    /** Establishes a live client. Idempotent for a healthy connection. */
    suspend fun open(id: ConnectionId): ConnectionView

    /** Releases a connection's client. Idempotent. */
    suspend fun close(id: ConnectionId): ConnectionView

    /**
     * The schemas of an open PostgreSQL connection, system schemas included only if
     * asked for.
     *
     * The three catalog reads are PostgreSQL's: a Redis connection is refused rather
     * than answered with an empty list, because an empty list is a claim about a
     * server and this one was never asked.
     */
    suspend fun schemas(id: ConnectionId, includeSystem: Boolean = false): Listing<SchemaInfo>

    /** The objects of one kind in one schema of an open PostgreSQL connection. */
    suspend fun objects(id: ConnectionId, schema: String, kind: ObjectKind): Listing<CatalogObject>

    /** The columns of one relation of an open PostgreSQL connection. */
    suspend fun columns(id: ConnectionId, schema: String, relation: String): List<ColumnInfo>

    /**
     * Runs one statement on an open PostgreSQL connection and returns its bounded
     * result.
     *
     * One statement, already chosen: splitting a script is
     * [dev.dbide.core.sql.EditorExecution]'s job and happens before anything is sent.
     * Cancelling the calling coroutine cancels the statement on the server, so
     * closing the editor that started it stops the work rather than orphaning it.
     */
    suspend fun execute(id: ConnectionId, sql: String): QueryResult

    /** Releases every client. Called during application shutdown. */
    suspend fun shutdown()
}

/**
 * The connection manager: it joins the configuration store, the secret vault, and
 * the runtime registry into the operations the UI calls.
 *
 * It is the only class that holds a decrypted password, and it holds one only for
 * the duration of a single operation.
 */
class DefaultConnectionService(
    private val store: ConfigStore,
    private val vault: Vault,
    private val registry: ConnectionRegistry,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> ConnectionId = { ConnectionId(UUID.randomUUID().toString()) },
) : ConnectionService {

    // --- Vault lifecycle -----------------------------------------------------

    /** Whether setup is required, and whether the vault is open. */
    override suspend fun vaultState(): VaultState = vault.state()

    /** Chooses the master password on first run. */
    override suspend fun setUp(password: Secret) = vault.setUp(password)

    /** Opens the vault with the master password. */
    override suspend fun unlock(password: Secret) = vault.unlock(password)

    /**
     * Discards the master key and closes every live client: a locked application
     * must not still be talking to production.
     */
    override suspend fun lock() {
        vault.lock()
        registry.closeAll()
    }

    // --- Connection CRUD -----------------------------------------------------

    /**
     * Every saved connection with its runtime state.
     *
     * Listing needs the vault open. Connection summaries carry no secrets, but they
     * are still a map of where this user's databases live.
     */
    override suspend fun list(): List<ConnectionView> {
        requireUnlocked()
        val states = registry.states()
        return store.list().map { record ->
            ConnectionView(
                summary = record.summarize(),
                // A connection the registry has never seen — every connection after a
                // restart — is closed, not statusless.
                runtime = states[record.id] ?: RuntimeState.CLOSED,
            )
        }
    }

    /** One saved connection with its runtime state. */
    override suspend fun get(id: ConnectionId): ConnectionView = view(record(id))

    /** Saves a new connection and seals its secret. */
    override suspend fun create(draft: ConnectionDraft): ConnectionView {
        requireUnlocked()
        val normalized = draft.normalized().validated()

        val config = normalized.toConfig(newId(), clock())
        val sealed = sealFor(config, normalized.secret, existing = null)
        val record = ConnectionRecord(config, sealed)

        store.create(record)
        return view(record)
    }

    /**
     * Replaces a connection's fields. The stored secret survives unless the caller
     * explicitly supplies a replacement.
     */
    override suspend fun update(id: ConnectionId, draft: ConnectionDraft): ConnectionView {
        val existing = record(id)
        val normalized = draft.normalized().validated()

        // created_at is immutable: an edit is not a new connection.
        val config = normalized.toConfig(id, existing.config.createdAt)
        val sealed = sealFor(config, normalized.secret, existing)
        val updated = ConnectionRecord(config, sealed)

        store.update(updated)

        // An open client built from the old settings must not survive them.
        try {
            withPassword(updated) { password -> registry.invalidateIfChanged(config, password) }
        } catch (unreadable: VaultException) {
            // The stored secret cannot be read back, so no client built from it can be
            // trusted either.
            registry.close(id)
            throw unreadable
        }
        return view(updated)
    }

    /**
     * Closes a connection's client and removes it. The client is released before the
     * record, so no runtime entry outlives its configuration.
     */
    override suspend fun delete(id: ConnectionId) {
        requireUnlocked()
        store.get(id)
        registry.forget(id)
        store.delete(id)
    }

    // --- Runtime operations --------------------------------------------------

    /** Dials, authenticates, and disconnects without touching the registry. */
    override suspend fun test(id: ConnectionId): TestResult {
        val record = record(id)
        return withPassword(record) { password ->
            when (record.config.engine) {
                Engine.POSTGRES -> PostgresProbe.test(PostgresConnectionConfig.of(record.config, password))
                Engine.REDIS -> RedisSession.test(record.config, password)
            }
        }
    }

    /**
     * Establishes a live client. Idempotent for a connection that is already healthy.
     *
     * A failure throws, but the registry has already recorded the status as `error`
     * with a safe message, so the list reflects it even if the caller only refreshes.
     */
    override suspend fun open(id: ConnectionId): ConnectionView {
        val record = record(id)
        withPassword(record) { password -> registry.open(record.config, password) }
        return view(record)
    }

    /** Releases a connection's client. Idempotent. */
    override suspend fun close(id: ConnectionId): ConnectionView {
        val record = record(id)
        registry.close(id)
        return view(record)
    }

    // --- Catalog -------------------------------------------------------------

    /**
     * The schemas of an open PostgreSQL connection.
     *
     * Browsing needs no decrypted password: the pool is already authenticated, so
     * these three go straight to the live session. A connection that is not open
     * throws rather than being opened implicitly — the user closed it, and a click on
     * a stale tree is not permission to dial production again.
     */
    override suspend fun schemas(id: ConnectionId, includeSystem: Boolean): Listing<SchemaInfo> =
        catalog(id).schemas(includeSystem)

    /** The objects of one kind in one schema of an open PostgreSQL connection. */
    override suspend fun objects(
        id: ConnectionId,
        schema: String,
        kind: ObjectKind,
    ): Listing<CatalogObject> = catalog(id).objects(schema, kind)

    /** The columns of one relation of an open PostgreSQL connection. */
    override suspend fun columns(
        id: ConnectionId,
        schema: String,
        relation: String,
    ): List<ColumnInfo> = catalog(id).columns(schema, relation)

    /**
     * Runs one statement on an open PostgreSQL connection.
     *
     * Like the catalog reads, this needs no decrypted password and refuses a
     * connection that is not open rather than dialing one. What the statement is
     * allowed to do is not decided here: the pool's read-only transaction decides it,
     * on the server, where a write hidden in a function body is still a write.
     */
    override suspend fun execute(id: ConnectionId, sql: String): QueryResult {
        requireUnlocked()
        return registry.postgres(id).adapter.execute(sql)
    }

    /** Releases every client. Called during application shutdown. */
    override suspend fun shutdown() = registry.closeAll()

    // --- Internals -----------------------------------------------------------

    private suspend fun catalog(id: ConnectionId): PostgresCatalog {
        requireUnlocked()
        return registry.postgres(id).catalog
    }

    private fun requireUnlocked() {
        if (!vault.isUnlocked) throw VaultLockedException()
    }

    private suspend fun record(id: ConnectionId): ConnectionRecord {
        requireUnlocked()
        return store.get(id)
    }

    private suspend fun view(record: ConnectionRecord) =
        ConnectionView(record.summarize(), registry.state(record.id))

    private fun ConnectionDraft.validated(): ConnectionDraft {
        val errors = validate()
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return this
    }

    /**
     * Runs [body] with the connection's decrypted password, then clears it. A
     * connection with no stored secret dials with an empty password, which is what
     * Redis and trust-authenticated PostgreSQL expect.
     */
    private suspend fun <T> withPassword(record: ConnectionRecord, body: suspend (Secret) -> T): T {
        val sealed = record.sealedSecret
        if (sealed == null || sealed.isEmpty()) return body(Secret.EMPTY)
        val password = vault.open(SecretIdentity.of(record.config), sealed)
        try {
            return body(password)
        } finally {
            password.clear()
        }
    }

    /**
     * Resolves the sealed secret for a create or update:
     *
     * - [SecretUpdate.Unchanged] keeps the stored bytes;
     * - [SecretUpdate.Clear] removes them;
     * - [SecretUpdate.Replace] seals the new password.
     *
     * When nothing changes but the connection's authenticated identity does — the
     * engine, in practice — the existing secret is opened and resealed so it stays
     * bound to its own record.
     */
    private suspend fun sealFor(
        config: ConnectionConfig,
        update: SecretUpdate,
        existing: ConnectionRecord?,
    ): ByteArray? = when (update) {
        is SecretUpdate.Clear -> null
        is SecretUpdate.Replace ->
            if (update.secret.isEmpty()) null else vault.seal(SecretIdentity.of(config), update.secret)

        is SecretUpdate.Unchanged -> {
            val stored = existing?.sealedSecret
            when {
                stored == null || stored.isEmpty() -> null
                SecretIdentity.of(existing.config) == SecretIdentity.of(config) -> stored
                else -> {
                    val password = vault.open(SecretIdentity.of(existing.config), stored)
                    try {
                        vault.seal(SecretIdentity.of(config), password)
                    } finally {
                        password.clear()
                    }
                }
            }
        }
    }
}
