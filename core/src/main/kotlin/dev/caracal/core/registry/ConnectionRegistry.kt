/**
 * The live database clients for open connections.
 *
 * Decrypted configuration lives here and nowhere else: never in a temporary file,
 * never in a log line, never in Compose state.
 */
package dev.caracal.core.registry

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.RuntimeState
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.connections.Secret
import dev.caracal.core.postgres.PostgresAdapter
import dev.caracal.core.postgres.PostgresConnectionConfig
import dev.caracal.core.postgres.PostgresSession
import dev.caracal.core.redis.RedisSession
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.result.asDbError
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

/** The open client for one connection. The engine travels with it, so a
 *  PostgreSQL request can never reach a Redis client by mistake. */
private sealed interface RuntimeClient : AutoCloseable {
    class Postgres(val session: PostgresSession) : RuntimeClient {
        override fun close() = session.close()
    }

    class Redis(val session: RedisSession) : RuntimeClient {
        override fun close() = session.close()
    }
}

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

    private class Entry(var engine: Engine) {
        /** Serializes dial and close for this connection only. */
        val operationLock = Mutex()
        var status: RuntimeStatus = RuntimeStatus.CLOSED
        var lastError: String? = null
        var openedAt: Instant? = null
        var fingerprint: String? = null
        var client: RuntimeClient? = null

        fun state() = RuntimeState(status = status, lastError = lastError, openedAt = openedAt)
    }

    /**
     * Establishes a client, or returns immediately if a healthy one already exists
     * for the same configuration and secret.
     */
    suspend fun open(config: ConnectionConfig, password: Secret) {
        val entry = entryFor(config.id, config.engine)
        val fingerprint = fingerprint(config, password)

        entry.operationLock.withLock {
            val alreadyOpen = stateLock.withLock {
                entry.status == RuntimeStatus.OPEN &&
                    entry.fingerprint == fingerprint &&
                    entry.engine == config.engine
            }
            // Open is idempotent for a healthy connection.
            if (alreadyOpen) return

            // A reopen with changed settings must not leave the old client behind.
            closeClient(entry)
            stateLock.withLock {
                entry.engine = config.engine
                entry.status = RuntimeStatus.OPENING
                entry.lastError = null
            }

            val client = try {
                when (config.engine) {
                    Engine.POSTGRES -> RuntimeClient.Postgres(
                        PostgresSession.open(
                            config = PostgresConnectionConfig.of(config, password),
                            statementTimeout = statementTimeout,
                        ),
                    )
                    Engine.REDIS -> RuntimeClient.Redis(RedisSession.open(config, password))
                }
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
        close(id)
        stateLock.withLock { entries.remove(id) }
    }

    /**
     * Closes a connection whose dialing configuration or secret no longer matches its
     * open client. Returns whether anything was closed.
     */
    suspend fun invalidateIfChanged(config: ConnectionConfig, password: Secret): Boolean {
        val stale = stateLock.withLock {
            val entry = entries[config.id] ?: return false
            entry.status == RuntimeStatus.OPEN && entry.fingerprint != fingerprint(config, password)
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

    /** The open PostgreSQL session for a connection. M2 reads through this. */
    suspend fun postgres(id: ConnectionId): PostgresSession =
        when (val client = openClient(id)) {
            is RuntimeClient.Postgres -> client.session
            else -> throw WrongEngineException()
        }

    /** The open Redis session for a connection. M3 reads through this. */
    suspend fun redis(id: ConnectionId): RedisSession =
        when (val client = openClient(id)) {
            is RuntimeClient.Redis -> client.session
            else -> throw WrongEngineException()
        }

    /** Releases every client. Called when the vault locks and at shutdown. */
    suspend fun closeAll() {
        val ids = stateLock.withLock { entries.keys.toList() }
        ids.forEach { close(it) }
    }

    private suspend fun openClient(id: ConnectionId): RuntimeClient = stateLock.withLock {
        val entry = entries[id] ?: throw NotOpenException()
        if (entry.status != RuntimeStatus.OPEN) throw NotOpenException()
        entry.client ?: throw NotOpenException()
    }

    private suspend fun entryFor(id: ConnectionId, engine: Engine): Entry = stateLock.withLock {
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
         */
        fun fingerprint(config: ConnectionConfig, password: Secret): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(
                config.engine.wire,
                config.host,
                config.port.toString(),
                config.database,
                config.username,
                config.tlsMode.wire,
                config.readOnly.toString(),
                password.expose(),
            ).forEach { part ->
                digest.update(part.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
