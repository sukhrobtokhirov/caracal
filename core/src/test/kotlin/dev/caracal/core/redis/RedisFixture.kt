package dev.caracal.core.redis

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.connections.networkConfig
import dev.caracal.engine.api.KeyValueLimits
import dev.caracal.engine.redis.RedisEngine
import io.lettuce.core.AclCategory
import io.lettuce.core.AclSetuserArgs
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.protocol.CommandType
import java.time.Instant
import org.testcontainers.containers.GenericContainer

/**
 * One disposable Redis, shared by every M3 integration suite.
 *
 * Shared rather than one per class because starting a container is most of what
 * these suites cost, and every claim they make is about a server rather than about a
 * fresh one. Isolation comes from database index instead: each suite owns one, and
 * flushes only its own, so nothing a suite does can be seen by another and none of
 * them has to run `FLUSHALL` on something a neighbour is mid-scan of.
 *
 * Everything here is initialized in an object initializer rather than lazily, and
 * that is load-bearing. A `by lazy` whose body reaches back through a property that
 * needs the same `lazy` re-enters it, and Kotlin's default synchronized mode does
 * not detect that — it simply runs the initializer again. With a container start
 * inside, the second run starts a second container, which starts a third. The
 * ordering below is the fix: the container, its address, and the client are all in
 * place before [seedUsers] runs, so nothing it calls can re-enter anything.
 */
object RedisFixture {

    /** Database indices, one per suite. Redis offers sixteen and this uses six. */
    const val BROWSE_DB = 1
    const val VALUE_DB = 2
    const val CONSOLE_DB = 3
    const val PERMISSIONS_DB = 4
    const val ENGINE_DB = 5

    /** An ACL user that may read keys and nothing else — §3.10's read-only column. */
    const val READER = "reader"

    /** An ACL user denied `INFO` and `MEMORY`, which §3.8 and §3.3 must survive. */
    const val NOSTATS = "nostats"

    const val ACL_PASSWORD = "acl-password"

    private val server: GenericContainer<*> = GenericContainer("redis:7-alpine")
        .withExposedPorts(6379)
        .apply { start() }

    val host: String = server.host

    val port: Int = server.firstMappedPort

    /**
     * One client for every fixture connection in the JVM.
     *
     * Not one per call. A `RedisClient` owns a Netty event loop whose threads are not
     * daemons, so a client that is never shut down keeps the test JVM alive after the
     * last test has passed — a green run that never exits, which reads as a hang
     * rather than as a leak.
     */
    private val client: RedisClient = RedisClient.create()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { client.shutdown() } })
        seedUsers()
    }

    /**
     * Arranges a test's data through a raw client, and closes the connection after.
     *
     * Deliberately not the adapter. A suite that built its fixtures through the code
     * under test would pass whenever that code was consistently wrong, and half of
     * what is asserted here is about commands the adapter must never issue — which it
     * cannot, so the setup has to.
     */
    fun <T> admin(database: Int, body: (RedisCommands<String, String>) -> T): T =
        connected(StringCodec.UTF8, database, body)

    /** As [admin], for arranging keys and values that are not text. */
    fun <T> adminBytes(database: Int, body: (RedisCommands<ByteArray, ByteArray>) -> T): T =
        connected(ByteArrayCodec.INSTANCE, database, body)

    private fun <K, V, T> connected(
        codec: RedisCodec<K, V>,
        database: Int,
        body: (RedisCommands<K, V>) -> T,
    ): T {
        val connection = client.connect(codec, RedisURI.Builder.redis(host, port).withDatabase(database).build())
        return connection.use { body(it.sync()) }
    }

    /** A saved connection pointing at this server, as the application would hold one. */
    fun config(
        database: Int,
        name: String = "fixture",
        readOnly: Boolean = false,
        environment: Environment = Environment.DEV,
        username: String = "",
    ) = networkConfig(
        id = ConnectionId("redis-fixture"),
        name = name,
        engineId = RedisEngine.ID,
        host = host,
        port = port,
        database = database.toString(),
        username = username,
        tlsMode = TlsMode.DISABLE,
        environment = environment,
        readOnly = readOnly,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    suspend fun session(
        database: Int,
        readOnly: Boolean = false,
        environment: Environment = Environment.DEV,
        name: String = "fixture",
        username: String = "",
        password: Secret = Secret.EMPTY,
        limits: KeyValueLimits = KeyValueLimits(),
    ): RedisSession = RedisSession.open(
        config = config(database, name = name, readOnly = readOnly, environment = environment, username = username),
        password = password,
        limits = limits,
    )

    private fun seedUsers() {
        admin(0) { commands ->
            // `@read` is Redis's own category for commands that only read, and it is a
            // stricter list than this build's allowlist. That is the arrangement §3.10
            // describes: the guard stops accidents, and the ACL is what actually holds.
            commands.aclSetuser(
                READER,
                AclSetuserArgs().on().addPassword(ACL_PASSWORD).allKeys()
                    .addCategory(AclCategory.READ)
                    .addCommand(CommandType.PING)
                    // `SELECT` is not in `@read`, and without it the connection cannot
                    // reach the database index the fixture puts its keys in — every
                    // test would fail at connect with a message about the wrong command.
                    .addCommand(CommandType.SELECT)
                    .addCommand(CommandType.INFO)
                    .addCommand(CommandType.SCAN)
                    .addCommand(CommandType.TYPE)
                    .addCommand(CommandType.TTL)
                    .addCommand(CommandType.MEMORY),
            )
            // Allowed to browse, refused the two commands the dashboard and the memory
            // column are built from. Rules apply left to right, so the removals land
            // after the category that granted them.
            commands.aclSetuser(
                NOSTATS,
                AclSetuserArgs().on().addPassword(ACL_PASSWORD).allKeys()
                    .addCategory(AclCategory.READ)
                    .addCommand(CommandType.PING)
                    .addCommand(CommandType.SELECT)
                    .addCommand(CommandType.SCAN)
                    .addCommand(CommandType.TYPE)
                    .addCommand(CommandType.TTL)
                    .removeCommand(CommandType.INFO)
                    .removeCommand(CommandType.MEMORY),
            )
        }
    }
}
