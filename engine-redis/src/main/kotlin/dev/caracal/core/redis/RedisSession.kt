/**
 * Adapts a stored connection to a live Redis client.
 *
 * As with PostgreSQL, the client is built from individual fields, so a password
 * never appears in a URL, a log line, or an error string.
 */
package dev.caracal.core.redis

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.text.Redaction
import dev.caracal.engine.api.KeyValueLimits
import dev.caracal.engine.api.TextValues
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * A live Redis connection, owned by whoever opened it.
 *
 * The connection is opened with [ByteArrayCodec], not the string codec, and that is
 * the decision the rest of M3 rests on. Every key, field, member, and value in Redis
 * is a byte string: a key can be a packed struct, a value can be a JPEG, and the
 * server neither knows nor cares. A string codec decodes all of that as UTF-8 and
 * substitutes a replacement character for whatever did not fit, which produces a key
 * name that cannot be sent back to fetch the value it names — the failure would look
 * like the key vanishing, on exactly the keys where it is hardest to guess why.
 * Deciding what is text is [TextValues]'s job, and it happens once, at the edge.
 */
class RedisSession private constructor(
    private val client: RedisClient,
    private val connection: StatefulRedisConnection<ByteArray, ByteArray>,
    config: ConnectionConfig,
    password: Secret,
    limits: KeyValueLimits,
) : AutoCloseable {

    /** Everything M3 does with this server. */
    val adapter: RedisAdapter = RedisAdapter(
        connection = connection,
        config = config,
        redaction = Redaction(identityOf(config), listOf(password.expose())),
        limits = limits,
    )

    /** One round trip, used to prove a new connection works. */
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
        throw DbException(RedisErrors.classify(failure))
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
        suspend fun open(
            config: ConnectionConfig,
            password: Secret,
            limits: KeyValueLimits = KeyValueLimits(),
        ): RedisSession = withContext(Dispatchers.IO) {
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
                client.connect(ByteArrayCodec.INSTANCE)
            } catch (failure: Throwable) {
                runCatching { client.shutdown() }
                throw DbException(RedisErrors.classify(failure))
            }
            val session = RedisSession(client, connection, config, password, limits)
            try {
                session.ping()
            } catch (failure: Throwable) {
                session.close()
                throw failure
            }
            session
        }

        /**
         * The strings that must never survive into a message or a log line.
         *
         * The Redis equivalent of `PostgresConnectionConfig.secrets()`, and the same
         * list for the same reason: Lettuce writes the address it dialled into most of
         * its connection failures, and the URI it builds carries the password when one
         * was given.
         */
        private fun identityOf(config: ConnectionConfig): List<String> = listOf(
            config.host,
            "${config.host}:${config.port}",
            config.username,
        )

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
                password.useChars { chars ->
                    // A username means Redis 6 ACL authentication; without one this is the
                    // legacy AUTH that sends the password alone.
                    if (config.username.isNotEmpty()) {
                        builder.withAuthentication(config.username, chars)
                    } else {
                        builder.withPassword(chars)
                    }
                }
            }
            return builder.build()
        }

        private const val CLIENT_NAME = "caracal"

        /** Pulls one `key:value` line out of an INFO section. */
        private fun infoField(info: String, key: String): String? = info.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')
            ?.takeIf { it.isNotBlank() }
    }
}
