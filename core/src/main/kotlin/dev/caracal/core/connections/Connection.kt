/**
 * The connection domain: value types, engine-aware defaults, and validation.
 *
 * This package deliberately knows nothing about SQL storage, encryption, or live
 * database clients. It is the vocabulary the other three speak.
 */
package dev.caracal.core.connections

import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.FormKeys
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant

/**
 * A password that will not leak through a log line, a stack trace, or a careless
 * string template. It is held as a [CharArray] and only turned into a `String` at
 * the moment a driver needs one.
 */
class Secret(private val chars: CharArray) {
    constructor(value: String) : this(value.toCharArray())

    fun expose(): String = String(chars)

    /** The backing array itself. `:core` internals use it to avoid making a `String`. */
    internal fun exposeChars(): CharArray = chars

    fun isEmpty(): Boolean = chars.isEmpty()

    /** Wipes the backing array. After this the secret cannot be used again. */
    fun clear() = chars.fill(' ')

    override fun toString(): String = "Secret(****)"

    companion object {
        val EMPTY: Secret get() = Secret(CharArray(0))
    }
}

/**
 * The database a connection talks to, named rather than enumerated.
 *
 * An enum was two engines' worth of correct and no more: a third one cannot be added
 * to it from another module, which is the whole of what Phase 3 is for. The stored
 * word is unchanged — a schema-3 database holds `postgres` and `redis` in the same
 * column it always did — and what changed is that reading one no longer has to
 * succeed. A connection naming an engine this build does not have is a connection
 * that lists, says so, and refuses to open, instead of a store that will not load.
 */
typealias EngineId = dev.caracal.engine.api.EngineId

/**
 * How dangerous a connection is.
 *
 * The type is `dev.caracal.engine.api.Environment` now, because it sits on
 * [dev.caracal.engine.api.ConnectionDescriptor] and the SPI cannot see `:core`. The
 * alias keeps every existing use of it — the badge, the sort order, the safety
 * policy's decision table — pointing at the same class it always did.
 */
typealias Environment = dev.caracal.engine.api.Environment

/** Transport security requested for a connection. */
enum class TlsMode(val wire: String) {
    DISABLE("disable"),
    REQUIRE("require"),
    VERIFY_FULL("verify-full"),
    ;

    companion object {
        val DEFAULT = DISABLE

        fun from(value: String?): TlsMode? =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }

        // Which modes an engine offers is no longer asked here. It is a
        // `FormField.Choice` in the engine's own connection form, and the options on
        // it are the answer — read through `DatabaseEngine.tlsModes` in
        // `dev.caracal.core.engines`. A list written here could only ever be right
        // about the engines it was written for.
    }
}

/**
 * A saved connection's identity. Distinct from its name, which the user can change.
 *
 * Moved into the SPI with [Environment], and for the same reason: a descriptor has
 * to name the connection it describes.
 */
typealias ConnectionId = dev.caracal.engine.api.ConnectionId

/**
 * The non-secret part of a connection: everything needed to dial a server except
 * the password.
 *
 * Host, port, database and user were fields here and are now derived. What the store
 * holds is [target] — what this connection points at, in the SPI's own sum type, so
 * that a file is expressible and not only a host and a port — and [settings], which
 * is whatever the engine's [dev.caracal.engine.api.ConnectionForm] declared, keyed by
 * [dev.caracal.engine.api.FormField.key] and otherwise unread.
 *
 * The accessors below are the small set of keys core does read, and each is named in
 * [FormKeys] with the reason. They are conveniences over [settings] rather than a
 * schema: an engine that declares no `user` field simply has an empty [username], and
 * nothing in core has to know which engines those are.
 */
data class ConnectionConfig(
    val id: ConnectionId,
    val name: String,
    val engineId: EngineId,
    val target: ConnectionTarget,
    /** Every non-secret field the engine declared, as the user left it. */
    val settings: Map<String, String> = emptyMap(),
    val environment: Environment = Environment.DEFAULT,
    val readOnly: Boolean = true,
    val color: String? = null,
    val createdAt: Instant = Instant.EPOCH,
) {
    private val network: ConnectionTarget.Network? get() = target as? ConnectionTarget.Network

    val host: String get() = network?.host.orEmpty()

    val port: Int get() = network?.port ?: 0

    val database: String get() = network?.database.orEmpty()

    /** The file this connection opens, for the engines that open one. */
    val path: Path? get() = (target as? ConnectionTarget.File)?.path

    val username: String get() = settings[FormKeys.USER].orEmpty()

    /** The stored transport mode. What it *means* is the engine's to say; see `Descriptors`. */
    val tlsMode: TlsMode get() = TlsMode.from(settings[FormKeys.TLS]) ?: TlsMode.DEFAULT

    /** The Redis database index. Non-numeric input cannot survive validation, so this is total. */
    val redisDatabaseIndex: Int get() = database.trim().toIntOrNull() ?: 0

    /** How this connection reads in a list: `localhost:5432`, or the file it opens. */
    val targetSummary: String
        get() = when (val target = target) {
            is ConnectionTarget.Network -> "${target.host}:${target.port}"
            is ConnectionTarget.File -> target.path.toString()
            // A URL is where credentials hide, and this one is shown in a list.
            is ConnectionTarget.Url -> target.raw.replace(CREDENTIALS_IN_URL, "$1")
            is ConnectionTarget.Cluster -> target.nodes.joinToString(", ") { "${it.host}:${it.port}" }
        }

    private companion object {
        /** The `user:password@` a URL target can carry, for stripping before display. */
        private val CREDENTIALS_IN_URL = Regex("(//)[^/@\\s]*@")
    }
}

/**
 * The stored form: a config plus its sealed secret. It never leaves `:core` — the
 * UI is handed a [ConnectionSummary] instead.
 */
class ConnectionRecord(val config: ConnectionConfig, val sealedSecret: ByteArray?) {
    val id: ConnectionId get() = config.id

    /** The safe projection: no secret, no sealed bytes, no encryption metadata. */
    fun summarize(): ConnectionSummary =
        ConnectionSummary(config = config, hasSecret = sealedSecret?.isNotEmpty() == true)
}

/**
 * A connection as the UI may see it. [hasSecret] reports that a password is stored
 * without revealing it or its length.
 */
data class ConnectionSummary(val config: ConnectionConfig, val hasSecret: Boolean)

/** A connection summary joined with its live runtime state. */
data class ConnectionView(val summary: ConnectionSummary, val runtime: RuntimeState) {
    val config: ConnectionConfig get() = summary.config
    val id: ConnectionId get() = summary.config.id
    val hasSecret: Boolean get() = summary.hasSecret
}

/** A connection's live client state, as tracked by the runtime registry. */
enum class RuntimeStatus { CLOSED, OPENING, OPEN, ERROR }

/**
 * The safe view of one runtime entry. [lastError] is an already-classified,
 * user-facing message: it never carries driver internals or credentials.
 */
data class RuntimeState(
    val status: RuntimeStatus = RuntimeStatus.CLOSED,
    val lastError: String? = null,
    val openedAt: Instant? = null,
) {
    val isOpen: Boolean get() = status == RuntimeStatus.OPEN

    companion object {
        val CLOSED = RuntimeState()
    }
}

/** What a successful connection test found. */
data class TestResult(
    val engineId: EngineId,
    val serverVersion: String?,
    val latencyMillis: Long,
)

/**
 * UTF-8 encodes a secret without an intermediate `String`, which the GC could hold
 * for hours. The caller owns the returned array and should clear it when done.
 */
internal fun Secret.toBytes(): ByteArray {
    val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(exposeChars()))
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    // The encoder's buffer is a second copy of the plaintext; do not leave it behind.
    buffer.clear()
    buffer.array().fill(0)
    return bytes
}

/** Decodes UTF-8 bytes into a secret without an intermediate `String`. */
internal fun secretOfBytes(bytes: ByteArray): Secret {
    val chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
    val out = CharArray(chars.remaining())
    chars.get(out)
    chars.clear()
    chars.array().fill(' ')
    return Secret(out)
}
