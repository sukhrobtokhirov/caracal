package dev.caracal.core.registry

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.connections.offersUnverifiedTls
import dev.caracal.core.connections.tlsModes
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.FormKeys
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.TlsConfig

/*
 * The stored form of a connection, in the shape an engine dials from.
 *
 * `ConnectionConfig` is what the store holds and the forms edit;
 * [ConnectionDescriptor] is what `DatabaseEngine.connect` takes. Phase 3 widened the
 * first to the second's shape — a target rather than a host and a port, declared
 * settings rather than named columns — so most of what this file did is gone. What
 * is left is the part that was never mechanical, and it is about TLS.
 */

/**
 * This connection, as the engine that dials it needs to see it.
 *
 * [ConnectionConfig.readOnly] is not on the descriptor: it is on `SessionPolicy`,
 * because it is the answer core resolved for *this* session rather than a label on
 * the record. [ConnectionDescriptor.writable] carries the label.
 */
internal fun ConnectionConfig.toDescriptor(engine: DatabaseEngine): ConnectionDescriptor =
    ConnectionDescriptor(
        id = id,
        engineId = engine.id,
        displayName = name,
        target = target,
        environment = environment,
        writable = !readOnly,
        tls = tlsConfig(engine),
        // Every declared, non-secret field, whatever the engine called them. The user
        // name travels here rather than in the bundle because it is not a secret and
        // is stored in the clear today; Phase 4 moves the pair into the vault, and
        // both engines already read the user from either place, so that move does not
        // come back to this file.
        engineOptions = settings,
    )

/**
 * The stored TLS mode, as the SPI spells it.
 *
 * The mapping is per engine and that is not an accident to be tidied away. The SPI
 * has two arms — off, and on-with-verification — because the product deliberately
 * does not offer encrypt-but-do-not-verify. PostgreSQL's stored `require` *is* that
 * third thing: pgjdbc encrypts and accepts any certificate. Redis's stored `require`
 * is not — the Redis client has always been built with `verifyPeer`, and `require`
 * is the one secure mode it offers. The same stored word means two different things.
 *
 * What decides which is meant is the engine's **own declaration** rather than its
 * name. An engine whose form offers both `require` and `verify-full` is drawing
 * PostgreSQL's distinction, so its `require` is the weaker of its two modes; an
 * engine offering `require` alone is saying that word *is* its secure mode. That
 * rule is right about an engine this file has never heard of, which a `when` on the
 * engine id could not be.
 *
 * A mode the engine does not offer is refused rather than downgraded. The draft
 * validation does not allow one to be saved, so reaching here means a config file
 * that was edited by hand — and answering that with a quietly weaker connection is
 * the one response that must not happen.
 */
private fun ConnectionConfig.tlsConfig(engine: DatabaseEngine): TlsConfig {
    val offered = engine.tlsModes
    if (offered.isNotEmpty() && tlsMode !in offered) {
        throw DbException(
            DbError.UnsupportedConfiguration(
                "${engine.displayName} supports the " +
                    offered.joinToString(" and ") { it.wire } + " TLS modes.",
            ),
        )
    }
    return when (tlsMode) {
        TlsMode.DISABLE -> TlsConfig.Disabled
        TlsMode.REQUIRE -> TlsConfig.Required(verifyHostname = !engine.offersUnverifiedTls)
        TlsMode.VERIFY_FULL -> TlsConfig.Required(verifyHostname = true)
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
    settings[FormKeys.USER].orEmpty().isNotEmpty() ->
        SecretBundle.UserPassword(username, password.expose().toCharArray())

    !password.isEmpty() -> SecretBundle.Password(password.expose().toCharArray())
    else -> SecretBundle.None
}
