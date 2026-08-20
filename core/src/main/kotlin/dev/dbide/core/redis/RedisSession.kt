/**
 * Adapts a stored connection to a live Redis client.
 *
 * As with PostgreSQL, the client is built from individual fields, so a password
 * never appears in a URL, a log line, or an error string.
 */
package dev.dbide.core.redis

import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Secret
import dev.dbide.core.connections.TestResult
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** A live Redis connection, owned by whoever opened it. */
class RedisSession private constructor(
    private val client: RedisClient,
    private val connection: StatefulRedisConnection<String, String>,
) : AutoCloseable {

    /** One round trip. M3 grows this into SCAN and paged value reads. */
    suspend fun ping(): String = command { connection.async().ping().await() }

    /**
     * The server's version, or null when the server restricts `INFO`. A server that
     * will not answer is still a working connection, so this failure is deliberately
     * swallowed.
     */
    suspend fun serverVersion(): String? = runCatching {
        command { connection.async().info("server").await() }
    }.getOrNull()?.let { infoField(it, "redis_version") }

    override fun close() {
        runCatching { connection.close() }.onFailure { log.debug("closing the Redis connection failed") }
        runCatching { client.shutdown() }.onFailure { log.debug("shutting down the Redis client failed") }
    }

    /** Runs a Lettuce call and converts any failure into a classified [DbException]. */
    private suspend fun <T> command(body: suspend () -> T): T = try {
        body()
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        throw DbException(RedisErrors.classify(failure), failure)
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisSession::class.java)

        /** Bounds DNS, TCP, and TLS together. */
        val CONNECT_TIMEOUT: Duration = 5.seconds

        /** Bounds a single command. */
        val COMMAND_TIMEOUT: Duration = 5.seconds

        /**
         * Opens a client and verifies it can reach the server once. A client that
         * cannot ping is closed here rather than handed back half-alive.
         */
        suspend fun open(config: ConnectionConfig, password: Secret): RedisSession =
            withContext(Dispatchers.IO) {
                val client = RedisClient.create(uri(config, password)).apply {
                    options = ClientOptions.builder()
                        .socketOptions(
                            SocketOptions.builder()
                                .connectTimeout(JavaDuration.ofMillis(CONNECT_TIMEOUT.inWholeMilliseconds))
                                .build(),
                        )
                        .build()
                }
                val connection = try {
                    client.connect()
                } catch (failure: Throwable) {
                    runCatching { client.shutdown() }
                    throw DbException(RedisErrors.classify(failure), failure)
                }
                val session = RedisSession(client, connection)
                try {
                    session.ping()
                } catch (failure: Throwable) {
                    session.close()
                    throw failure
                }
                session
            }

        /** Dials, authenticates, reads the server version, and disconnects. */
        suspend fun test(config: ConnectionConfig, password: Secret): TestResult {
            val started = TimeSource.Monotonic.markNow()
            return open(config, password).use { session ->
                val latency = started.elapsedNow().inWholeMilliseconds
                TestResult(
                    engine = Engine.REDIS,
                    serverVersion = session.serverVersion(),
                    latencyMillis = latency,
                )
            }
        }

        private fun uri(config: ConnectionConfig, password: Secret): RedisURI {
            val builder = RedisURI.Builder.redis(config.host, config.port)
                .withDatabase(config.redisDatabaseIndex)
                .withClientName(CLIENT_NAME)
                .withTimeout(JavaDuration.ofMillis(COMMAND_TIMEOUT.inWholeMilliseconds))

            when (config.tlsMode) {
                TlsMode.DISABLE -> Unit
                // v0.1 offers Redis one secure mode, and it is the one that checks the
                // certificate. Encrypt-but-do-not-verify is not on the menu.
                TlsMode.REQUIRE -> builder.withSsl(true).withVerifyPeer(true)
                TlsMode.VERIFY_FULL -> throw DbException(
                    DbError.UnsupportedConfiguration("Redis supports the disable and require TLS modes."),
                )
            }

            if (!password.isEmpty()) {
                // A username means Redis 6 ACL authentication; without one this is the
                // legacy AUTH that sends the password alone.
                if (config.username.isNotEmpty()) {
                    builder.withAuthentication(config.username, password.exposeChars())
                } else {
                    builder.withPassword(password.exposeChars())
                }
            }
            return builder.build()
        }

        private const val CLIENT_NAME = "dbide"

        /** Pulls one `key:value` line out of an INFO section. */
        private fun infoField(info: String, key: String): String? = info.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')
            ?.takeIf { it.isNotBlank() }
    }
}
