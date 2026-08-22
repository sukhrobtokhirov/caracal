package dev.caracal.engine.api

import java.nio.file.Path

/** A saved connection's identity. Distinct from its name, which the user can change. */
@JvmInline
value class ConnectionId(val value: String) {
    override fun toString(): String = value
}

/**
 * Everything an engine needs to dial one server, except the secret.
 *
 * The current stored form is host, port, database, user, password, and SQLite breaks
 * it on the first day: a file path and no credentials at all. [target] is that
 * generalization, made now rather than when it is urgent, so the widening is a
 * change to one type instead of a change to every form that reads it.
 *
 * Two fields §5.1 of the spec puts here are deliberately absent.
 *
 * `environment` is one. Which environment a connection is tagged with decides
 * whether a write needs a click or a typed name, and no engine has any use for that:
 * it dials a server. §7 already splits the responsibility this way — classification
 * per engine, policy in core — and carrying the tag across the boundary would invite
 * an engine to read it. Core keeps it, on its own record, where the decision is made.
 *
 * `writable` is the other, and it is here in spirit as [SessionPolicy.readOnly].
 * The distinction is worth the extra type: `writable` is a label on a saved
 * connection, and what the engine needs at connect time is the answer core resolved
 * for *this* session.
 */
data class ConnectionDescriptor(
    val id: ConnectionId,
    val engineId: EngineId,
    val displayName: String,
    val target: ConnectionTarget,
    val transport: Transport = Transport.Direct,
    val tls: TlsConfig = TlsConfig.Disabled,
    /** Null for SQLite and for an unauthenticated Redis. */
    val secretRef: SecretRef? = null,
    /** Engine specific, and validated by the engine that understands them. */
    val engineOptions: Map<String, String> = emptyMap(),
)

/** What a connection points at. Not every engine points at a host and a port. */
sealed interface ConnectionTarget {
    data class Network(val host: String, val port: Int, val database: String? = null) : ConnectionTarget

    data class File(val path: Path, val createIfMissing: Boolean = false) : ConnectionTarget

    /** The escape hatch. Redact on display: a URL is where credentials hide. */
    data class Url(val raw: String) : ConnectionTarget

    data class Cluster(val nodes: List<HostPort>) : ConnectionTarget
}

data class HostPort(val host: String, val port: Int)

/** How the bytes get there. */
sealed interface Transport {
    data object Direct : Transport

    data class SshTunnel(
        val host: String,
        val port: Int,
        val user: String,
        val auth: SshAuthRef,
        val localBindPort: Int? = null,
    ) : Transport
}

/** A handle to SSH credentials held in the vault. Never the credentials themselves. */
@JvmInline
value class SshAuthRef(val value: String)

/**
 * Transport security, as the connection asked for it.
 *
 * [Required] is encrypt-and-verify. There is no encrypt-but-do-not-verify arm,
 * because the product does not offer one: a mode that encrypts and accepts any
 * certificate defends against nothing an attacker on the path cannot do anyway, and
 * offering it means someone will pick it.
 */
sealed interface TlsConfig {
    data object Disabled : TlsConfig

    data class Required(val verifyHostname: Boolean = true) : TlsConfig
}

/** A handle to a secret in the vault. Resolving it is core's job, not an engine's. */
@JvmInline
value class SecretRef(val value: String)

/**
 * The resolved safety decision for one session, as the engine needs it.
 *
 * §3.1 of the spec passes `DataSafetyPolicy` itself here, so that a driver can apply
 * server-side read-only enforcement at connection time. The repository wins on the
 * type: `DataSafetyPolicy` is a decision table that answers "what must the user
 * agree to before this statement is sent", and an engine has nothing to ask it. What
 * an engine needs is the answer, and [readOnly] is the whole of it.
 *
 * [statementTimeout] rides along because it is already configured at exactly this
 * seam — the registry sets it once, every session opened there is given it — and
 * because a timeout that has to be threaded through every caller of `execute` is a
 * timeout that will eventually not be.
 */
data class SessionPolicy(
    val readOnly: Boolean,
    val statementTimeout: kotlin.time.Duration,
)
