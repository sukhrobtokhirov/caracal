/**
 * The live database clients for open connections.
 *
 * Decrypted configuration lives here and nowhere else: never in a temporary file,
 * never in a log line, never in Compose state.
 */
package dev.caracal.core.registry

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.EngineId
import dev.caracal.core.connections.RuntimeState
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.connections.charsToUtf8
import dev.caracal.core.engines.Engines
import dev.caracal.core.postgres.PostgresAdapter
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.result.asDbError
import dev.caracal.core.vault.wipe
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.postgres.PostgresEngineSession
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Reported when a request needs a client that is not established. */
class NotOpenException :
    DbException(DbError.ConnectionFailed("This connection is not open."))

/** Reported when an engine-specific request is made against another engine. */
class WrongEngineException :
    DbException(DbError.UnsupportedConfiguration("This operation does not apply to this connection's engine."))

/**
 * Maps connection identifiers to live clients.
 *
 * Two levels of locking: [stateLock] is held only for fast map reads and writes, so
 * a status read never waits behind a five-second dial, while a per-connection lock
 * serializes the slow operations for that one connection.
 *
 * [statementTimeout] is §2.4's configurable statement timeout, and this is the one
 * place it is set. Every PostgreSQL session opened here is given it, so changing
 * the limit is changing one value rather than auditing every call site that runs a
 * statement — and a test that needs a query to time out can inject a short one.
 */
class ConnectionRegistry(
    private val statementTimeout: Duration = PostgresAdapter.DEFAULT_STATEMENT_TIMEOUT,
) {
    private val stateLock = Mutex()
    private val entries = LinkedHashMap<ConnectionId, Entry>()

    private class Entry(var engine: EngineId) {
        /** Serializes dial and close for this connection only. */
        val operationLock = Mutex()
        var status: RuntimeStatus = RuntimeStatus.CLOSED
        var lastError: String? = null
        var openedAt: Instant? = null
        var fingerprint: String? = null
        var client: DatabaseSession? = null

        fun state() = RuntimeState(status = status, lastError = lastError, openedAt = openedAt)
    }

    /**
     * Establishes a client, or returns immediately if a healthy one already exists
     * for the same configuration and secret.
     */
    suspend fun open(config: ConnectionConfig, secret: SecretBundle) {
        val entry = entryFor(config.id, config.engineId)
        val fingerprint = fingerprint(config, secret)

        entry.operationLock.withLock {
            val alreadyOpen = stateLock.withLock {
                entry.status == RuntimeStatus.OPEN &&
                    entry.fingerprint == fingerprint &&
                    entry.engine == config.engineId
            }
            // Open is idempotent for a healthy connection.
            if (alreadyOpen) return

            // A reopen with changed settings must not leave the old client behind.
            closeClient(entry)
            stateLock.withLock {
                entry.engine = config.engineId
                entry.status = RuntimeStatus.OPENING
                entry.lastError = null
            }

            val engine = Engines.require(config.engineId)
            val client = try {
                engine.connect(
                    descriptor = config.toDescriptor(engine),
                    secrets = config.resolveSecret(secret),
                    policy = SessionPolicy(readOnly = config.readOnly, statementTimeout = statementTimeout),
                )
            } catch (cancellation: CancellationException) {
                stateLock.withLock { entry.reset(RuntimeStatus.CLOSED) }
                throw cancellation
            } catch (failure: Throwable) {
                val error = failure.asDbError()
                stateLock.withLock {
                    entry.reset(RuntimeStatus.ERROR)
                    entry.lastError = error.message
                }
                throw if (failure is DbException) failure else DbException(error, failure)
            }

            stateLock.withLock {
                entry.client = client
                entry.status = RuntimeStatus.OPEN
                entry.lastError = null
                entry.openedAt = Instant.now()
                entry.fingerprint = fingerprint
            }
        }
    }

    /** Releases a connection's client. Idempotent. */
    suspend fun close(id: ConnectionId) {
        val entry = stateLock.withLock { entries[id] } ?: return
        entry.operationLock.withLock {
            closeClient(entry)
            stateLock.withLock { entry.reset(RuntimeStatus.CLOSED) }
        }
    }

    /**
     * Closes a connection and drops its entry entirely. Used when the connection is
     * deleted, so no phantom entry survives its record.
     */
    suspend fun forget(id: ConnectionId) {
        val entry = stateLock.withLock { entries[id] } ?: return
        // The close and the removal are one operation under the entry's own lock.
        // Doing them as two left a window between them in which a concurrent `open`
        // could take this same entry back out of the map, dial, and store a live
        // client on it — after which the removal made that client unreachable from
        // `entries`, so closeAll, shutdown, and lock all walked past it and the pool
        // survived until the process died.
        entry.operationLock.withLock {
            closeClient(entry)
            stateLock.withLock {
                entry.reset(RuntimeStatus.CLOSED)
                // Only if it is still this entry: an `open` that already replaced it
                // owns what is in the map now.
                if (entries[id] === entry) entries.remove(id)
            }
        }
    }

    /**
     * Closes a connection whose dialing configuration or secret no longer matches its
     * open client. Returns whether anything was closed.
     */
    suspend fun invalidateIfChanged(config: ConnectionConfig, secret: SecretBundle): Boolean {
        val stale = stateLock.withLock {
            val entry = entries[config.id] ?: return false
            entry.status == RuntimeStatus.OPEN && entry.fingerprint != fingerprint(config, secret)
        }
        if (stale) close(config.id)
        return stale
    }

    /** One connection's runtime state. A connection never opened is closed, not statusless. */
    suspend fun state(id: ConnectionId): RuntimeState =
        stateLock.withLock { entries[id]?.state() ?: RuntimeState.CLOSED }

    /** Every tracked connection's runtime state. */
    suspend fun states(): Map<ConnectionId, RuntimeState> =
        stateLock.withLock { entries.mapValues { (_, entry) -> entry.state() } }

    /**
     * The open session for a connection, as the SPI sees it.
     *
     * The one accessor, and everything the UI reaches is a facet off it. There used
     * to be two typed ones — `postgres(id)` and `redis(id)` — and their loss is the
     * point of Phase 2: a caller that could ask for a `RedisSession` was a caller
     * that had to know which engine it was talking to before it could ask anything.
     */
    suspend fun session(id: ConnectionId): DatabaseSession = openClient(id)

    /**
     * The open PostgreSQL adapter, for the two calls no facet covers yet.
     *
     * `execute` and `exportCsv` still go through it, because [dev.caracal.engine.api.QueryFacet]
     * streams outcomes and `:core` returns a whole `QueryResult` — reconciling those
     * is a change to the result model, the error position mapping that rides on it,
     * and every grid that reads one, which is not a change to make in the same phase
     * as a module boundary. Phase 2 stops here on purpose and says so.
     *
     * It is not a hole in the boundary: `:core` may see engines, and this is `:core`.
     * What must not compile against an engine is the UI, and the UI cannot reach
     * this.
     */
    suspend fun postgresAdapter(id: ConnectionId): PostgresAdapter =
        (openClient(id) as? PostgresEngineSession)?.adapter ?: throw WrongEngineException()

    /** Releases every client. Called when the vault locks and at shutdown. */
    suspend fun closeAll() {
        val ids = stateLock.withLock { entries.keys.toList() }
        ids.forEach { close(it) }
    }

    private suspend fun openClient(id: ConnectionId): DatabaseSession = stateLock.withLock {
        val entry = entries[id] ?: throw NotOpenException()
        if (entry.status != RuntimeStatus.OPEN) throw NotOpenException()
        entry.client ?: throw NotOpenException()
    }

    private suspend fun entryFor(id: ConnectionId, engine: EngineId): Entry = stateLock.withLock {
        entries.getOrPut(id) { Entry(engine) }
    }

    /** Releases whatever client the entry holds. The caller holds its operation lock. */
    private suspend fun closeClient(entry: Entry) {
        val client = stateLock.withLock { entry.client.also { entry.client = null } } ?: return
        // Closing a pool blocks, and a cancelled caller must still not leak it.
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { client.close() }
                .onFailure { log.debug("closing a database client failed") }
        }
    }

    private fun Entry.reset(to: RuntimeStatus) {
        status = to
        lastError = null
        openedAt = null
        fingerprint = null
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConnectionRegistry::class.java)

        /**
         * Summarizes everything that affects how a client dials or behaves, including
         * the secret. Comparing fingerprints detects a configuration change without
         * keeping the password around to compare against.
         *
         * `readOnly` is in here because it is not a label on a connection, it is how
         * the pool is built: turning it off and leaving an already-open pool in place
         * would leave a connection the user has just marked writable still refusing
         * writes, and turning it on would leave one still accepting them. The second
         * is the one that matters.
         *
         * `environment` is in here for the same reason one step removed. The Redis
         * adapter captures the config at open, and the command guard reads the
         * environment off it: without this, re-labelling an open connection as
         * production left `FLUSHDB` behind a single click instead of the typed
         * confirmation, on the connection the user had just declared production.
         */
        fun fingerprint(config: ConnectionConfig, secret: SecretBundle): String {
            val digest = MessageDigest.getInstance("SHA-256")
            // A zero after each part, so that two configurations differing only in
            // where one field ends and the next begins cannot hash the same.
            fun part(bytes: ByteArray) {
                digest.update(bytes)
                digest.update(0)
            }

            fun part(text: String) = part(text.toByteArray(Charsets.UTF_8))

            /** The secret's characters, digested without ever becoming a `String`. */
            fun secretPart(chars: CharArray) {
                val bytes = charsToUtf8(chars)
                try {
                    part(bytes)
                } finally {
                    bytes.wipe()
                }
            }

            part(config.engineId.value)
            // The whole target and every declared setting, rather than the five
            // fields a connection used to have. An engine is free to declare a
            // field this file has never heard of, and a change to one of those is
            // as much a reason to redial as a change to the host.
            part(config.target.toString())
            config.settings.toSortedMap().forEach { (key, value) ->
                part(key)
                part(value)
            }
            part(config.readOnly.toString())
            part(config.environment.wire)
            // The kind is hashed as well as the fields: swapping a password for a
            // connection string that happens to read the same is still a redial.
            when (secret) {
                is SecretBundle.None -> part("none")
                is SecretBundle.Password -> {
                    part("password")
                    secretPart(secret.password)
                }

                is SecretBundle.UserPassword -> {
                    part("user_password")
                    part(secret.user)
                    secretPart(secret.password)
                }

                is SecretBundle.ClientCertificate -> {
                    part("client_certificate")
                    part(secret.keyStore)
                    secretPart(secret.passphrase)
                }

                is SecretBundle.ConnectionString -> {
                    part("connection_string")
                    secretPart(secret.value)
                }

                is SecretBundle.Token -> {
                    part("token")
                    secretPart(secret.value)
                    part(secret.expiresAt?.toString().orEmpty())
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
