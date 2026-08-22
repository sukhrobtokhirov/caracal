/**
 * The connection domain: value types, engine-aware defaults, and validation.
 *
 * This package deliberately knows nothing about SQL storage, encryption, or live
 * database clients. It is the vocabulary the other three speak.
 */
package dev.caracal.core.connections

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
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

/** The database a connection talks to. */
enum class Engine(val wire: String, val defaultPort: Int) {
    POSTGRES("postgres", 5432),
    REDIS("redis", 6379),
    ;

    companion object {
        fun from(value: String?): Engine? =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

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

        /** Redis gets `disable` and `require` in v0.1; `verify-full` is PostgreSQL only. */
        fun supportedBy(engine: Engine): List<TlsMode> = when (engine) {
            Engine.POSTGRES -> listOf(DISABLE, REQUIRE, VERIFY_FULL)
            Engine.REDIS -> listOf(DISABLE, REQUIRE)
        }
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
 */
data class ConnectionConfig(
    val id: ConnectionId,
    val name: String,
    val engine: Engine,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val tlsMode: TlsMode,
    val environment: Environment,
    val readOnly: Boolean,
    val color: String?,
    val createdAt: Instant,
) {
    /** The Redis database index. Non-numeric input cannot survive validation, so this is total. */
    val redisDatabaseIndex: Int get() = database.trim().toIntOrNull() ?: 0
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
    val engine: Engine,
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
