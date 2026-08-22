package dev.caracal.core.registry

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.EngineId
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.TlsConfig
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.redis.RedisEngine

/*
 * The stored form of a connection, in the shape an engine dials from.
 *
 * `ConnectionConfig` is what the store holds and the forms edit;
 * [ConnectionDescriptor] is what `DatabaseEngine.connect` takes. They say the same
 * things and neither can be deleted yet — the descriptor cannot carry a colour or a
 * creation time, and the config cannot carry a file path, which is what SQLite will
 * need. So this is a translation and it lives in one place, next to the registry
 * that is its only caller.
 *
 * Phase 3 is where the stored form widens to the descriptor's shape and this
 * shrinks to nothing. Until then, the two facts worth knowing are both about TLS.
 */

/** Both engines spell the connecting user's option key the same way. */
private val USER_OPTION: String = PostgresEngine.OPTION_USER.also { check(it == RedisEngine.OPTION_USER) }

/** The engine this connection is for, by the wire name both sides already use. */
internal fun Engine.asEngineId(): EngineId = when (this) {
    Engine.POSTGRES -> PostgresEngine.ID
    Engine.REDIS -> RedisEngine.ID
}

/**
 * This connection, as the engine that dials it needs to see it.
 *
 * [readOnly] is not on the descriptor: it is on `SessionPolicy`, because it is the
 * answer core resolved for *this* session rather than a label on the record.
 * [ConnectionDescriptor.writable] carries the label.
 */
internal fun ConnectionConfig.toDescriptor(): ConnectionDescriptor = ConnectionDescriptor(
    id = id,
    engineId = engine.asEngineId(),
    displayName = name,
    target = ConnectionTarget.Network(host = host, port = port, database = database),
    environment = environment,
    writable = !readOnly,
    tls = tlsConfig(),
    // Not a secret and stored in the clear today, which is why it travels here and
    // not in the bundle. Phase 4 moves the pair into the vault; both engines already
    // read the user from either place, so that move does not come back to this file.
    engineOptions = mapOf(USER_OPTION to username),
)

/**
 * The stored TLS mode, as the SPI spells it.
 *
 * The mapping is per engine and that is not an accident to be tidied away. The SPI
 * has two arms — off, and on-with-verification — because the product deliberately
 * does not offer encrypt-but-do-not-verify. PostgreSQL's stored `require` *is* that
 * third thing: pgjdbc encrypts and accepts any certificate. Redis's stored `require`
 * is not — the Redis client has always been built with `verifyPeer`, and `require`
 * is the one secure mode it offers. So the same stored word means two different
 * things and is translated as such, rather than being flattened into whichever
 * reading is convenient. Flattening it either way would silently change what a saved
 * connection does: a Redis connection would stop checking certificates, or a
 * PostgreSQL one would start and fail against the self-signed servers it works with
 * today.
 *
 * `verify-full` on Redis is refused rather than downgraded. The draft validation
 * does not allow it to be saved, so reaching here means a config file that was
 * edited by hand — and answering that with a quietly weaker connection is the one
 * response that must not happen. The sentence is the one the Redis client itself
 * used to raise.
 */
private fun ConnectionConfig.tlsConfig(): TlsConfig = when (engine) {
    Engine.POSTGRES -> when (tlsMode) {
        TlsMode.DISABLE -> TlsConfig.Disabled
        TlsMode.REQUIRE -> TlsConfig.Required(verifyHostname = false)
        TlsMode.VERIFY_FULL -> TlsConfig.Required(verifyHostname = true)
    }

    Engine.REDIS -> when (tlsMode) {
        TlsMode.DISABLE -> TlsConfig.Disabled
        TlsMode.REQUIRE -> TlsConfig.Required(verifyHostname = true)
        TlsMode.VERIFY_FULL -> throw DbException(
            DbError.UnsupportedConfiguration("Redis supports the disable and require TLS modes."),
        )
    }
}

/**
 * The resolved secret, in whichever shape this connection authenticates with.
 *
 * A copy of the characters, because the bundle outlives this call by exactly as long
 * as the dial takes and the caller clears its own [Secret] the moment that returns.
 * A connection with neither a user nor a password gets [SecretBundle.None], which is
 * a statement — SQLite and an unauthenticated Redis — and not "we could not find
 * one".
 */
internal fun ConnectionConfig.secretBundle(password: Secret): SecretBundle = when {
    username.isNotEmpty() -> SecretBundle.UserPassword(username, password.expose().toCharArray())
    !password.isEmpty() -> SecretBundle.Password(password.expose().toCharArray())
    else -> SecretBundle.None
}
