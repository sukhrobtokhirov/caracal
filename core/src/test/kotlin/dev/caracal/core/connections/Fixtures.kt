package dev.caracal.core.connections

import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.EngineId
import dev.caracal.engine.api.FormKeys
import dev.caracal.engine.postgres.PostgresEngine
import java.time.Instant

/**
 * A saved connection to a server, in the shape tests kept writing out by hand.
 *
 * `ConnectionConfig` stopped being host-port-database-user shaped in Phase 3, and
 * about forty tests do not care: they need *a* connection so that they can assert
 * something else. This keeps the shape change in one place rather than in all of
 * them, and it is deliberately network-only — a test about a file target should say
 * so by building the config itself.
 */
fun networkConfig(
    id: ConnectionId = ConnectionId("test-connection"),
    name: String = "Test",
    engineId: EngineId = PostgresEngine.ID,
    host: String = "localhost",
    port: Int = 5432,
    database: String = "caracal",
    username: String = "",
    tlsMode: TlsMode = TlsMode.DISABLE,
    environment: Environment = Environment.DEV,
    readOnly: Boolean = true,
    color: String? = null,
    createdAt: Instant = Instant.parse("2026-08-20T10:00:00Z"),
    settings: Map<String, String> = emptyMap(),
): ConnectionConfig = ConnectionConfig(
    id = id,
    name = name,
    engineId = engineId,
    target = ConnectionTarget.Network(host = host, port = port, database = database),
    settings = buildMap {
        put(FormKeys.TLS, tlsMode.wire)
        if (username.isNotEmpty()) put(FormKeys.USER, username)
        putAll(settings)
    },
    environment = environment,
    readOnly = readOnly,
    color = color,
    createdAt = createdAt,
)

/**
 * A draft for a connection to a server, with only the fields a test cares about.
 *
 * A null means "do not type anything into that field", which is what makes the
 * engine's declared default apply — the behaviour most of these tests are asserting
 * around rather than asserting on.
 */
fun networkDraft(
    engineId: EngineId = PostgresEngine.ID,
    name: String = "Test",
    host: String? = "localhost",
    port: Int? = null,
    database: String? = null,
    username: String? = null,
    tlsMode: TlsMode? = null,
    environment: Environment = Environment.DEV,
    readOnly: Boolean = true,
    color: String? = null,
    secret: SecretUpdate = SecretUpdate.Unchanged,
): ConnectionDraft = ConnectionDraft(
    engineId = engineId,
    name = name,
    values = buildMap {
        host?.let { put(FormKeys.HOST, it) }
        port?.let { put(FormKeys.PORT, it.toString()) }
        database?.let { put(FormKeys.DATABASE, it) }
        username?.let { put(FormKeys.USER, it) }
        tlsMode?.let { put(FormKeys.TLS, it.wire) }
    },
    environment = environment,
    readOnly = readOnly,
    color = color,
    secret = secret,
)
