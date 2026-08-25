package dev.caracal.app

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionDraft
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.fields
import dev.caracal.core.connections.SecretUpdate
import dev.caracal.core.connections.TlsMode
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.EngineId
import dev.caracal.engine.api.FormKeys
import java.time.Instant

/** The identifiers the bundled engines register under, for tests that name one. */
val POSTGRES: EngineId = EngineId("postgres")

val REDIS: EngineId = EngineId("redis")

/**
 * A saved connection to a server, in the shape these tests kept writing out by hand.
 *
 * `ConnectionConfig` holds a target and a map of declared settings since Phase 3, and
 * the forty-odd tests that need *a* connection so they can assert something else do
 * not care. `:core` has the same helper for the same reason; a test module cannot see
 * another module's test sources, which is the whole of why there are two.
 */
fun networkConfig(
    id: ConnectionId = ConnectionId("test-connection"),
    name: String = "Test",
    engineId: EngineId = POSTGRES,
    host: String = "localhost",
    port: Int = 5432,
    database: String = "caracal",
    username: String = "caracal",
    tlsMode: TlsMode = TlsMode.DISABLE,
    environment: Environment = Environment.DEV,
    readOnly: Boolean = true,
    color: String? = null,
    createdAt: Instant = Instant.parse("2026-08-20T10:00:00Z"),
): ConnectionConfig = ConnectionConfig(
    id = id,
    name = name,
    engineId = engineId,
    target = ConnectionTarget.Network(host = host, port = port, database = database),
    settings = buildMap {
        put(FormKeys.TLS, tlsMode.wire)
        if (username.isNotEmpty()) put(FormKeys.USER, username)
    },
    environment = environment,
    readOnly = readOnly,
    color = color,
    createdAt = createdAt,
)

/** A draft for a connection to a server. A null field is one nothing was typed into. */
fun networkDraft(
    engineId: EngineId = POSTGRES,
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

/**
 * Types into a declared field by key, the way the dialog does.
 *
 * The dialog hands [ConnectionFormState.onValue] the field it drew; a test knows the
 * key and not the declaration, so this looks the field up. It fails loudly for a key
 * the chosen engine does not declare, which is the mistake worth catching.
 */
fun ConnectionFormState.type(key: String, value: String) {
    val field = engine.fields.firstOrNull { it.key == key }
        ?: error("${engine.id} declares no field named $key")
    onValue(field, value)
}
