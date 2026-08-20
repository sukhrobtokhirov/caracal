package dev.dbide.core.connections

import java.net.InetAddress
import java.time.Instant

/**
 * What to do with the stored password on a create or update.
 *
 * The Go implementation needed a `{changed, value}` pair because JSON has no sum
 * type and an empty password field must not be confused with "leave it alone".
 * In Kotlin the three cases are three cases, and the ambiguity cannot be
 * expressed: a form that posts an empty field sends [Unchanged], not [Clear].
 */
sealed interface SecretUpdate {
    /** Keep whatever is stored. The default, and what an edit form does by itself. */
    data object Unchanged : SecretUpdate

    /** Deliberately remove the stored password. */
    data object Clear : SecretUpdate

    /** Replace the stored password. */
    data class Replace(val secret: Secret) : SecretUpdate
}

/**
 * A create or update request. [port] and [tlsMode] are nullable so the engine
 * default can be applied when the caller omits them, without treating an explicit
 * `0` or `""` as absent.
 */
data class ConnectionDraft(
    val name: String = "",
    val engine: Engine = Engine.POSTGRES,
    val host: String = "",
    val port: Int? = null,
    val database: String = "",
    val username: String = "",
    val tlsMode: TlsMode? = null,
    val environment: Environment = Environment.DEFAULT,
    /**
     * Defaults to read only, because §2.4 makes this flag decide whether the pool
     * opens its connections in a PostgreSQL `READ ONLY` transaction — and a new
     * connection should not be able to write before anyone has thought about whether
     * it should. Turning it off is one visible click.
     */
    val readOnly: Boolean = true,
    val color: String? = null,
    val secret: SecretUpdate = SecretUpdate.Unchanged,
) {
    /**
     * Trims whitespace and applies engine defaults. Runs before [validate], so the
     * defaults are themselves validated rather than trusted.
     */
    fun normalized(): ConnectionDraft = copy(
        name = name.trim(),
        host = host.trim(),
        database = database.trim().ifEmpty { if (engine == Engine.REDIS) "0" else "" },
        username = username.trim(),
        port = port ?: engine.defaultPort,
        tlsMode = tlsMode ?: TlsMode.DEFAULT,
        color = color?.trim()?.ifEmpty { null },
    )

    /**
     * Every problem with this draft, so the form can show them together rather than
     * one per round trip. An empty list means the draft is usable.
     */
    fun validate(): List<ValidationError> = buildList {
        when {
            name.isEmpty() -> add(ValidationError.NAME, "A name is required.")
            name.length > MAX_NAME -> add(ValidationError.NAME, "A name may be at most $MAX_NAME characters.")
        }

        validateHost()

        val port = port ?: engine.defaultPort
        if (port < 1 || port > 65535) {
            add(ValidationError.PORT, "A port must be between 1 and 65535.")
        }

        if (username.length > MAX_USERNAME) {
            add(ValidationError.USERNAME, "A username may be at most $MAX_USERNAME characters.")
        }
        if (color != null && !COLOR.matches(color)) {
            add(ValidationError.COLOR, "A color must be a hex value such as #4c8dff.")
        }

        val tls = tlsMode ?: TlsMode.DEFAULT
        if (tls !in TlsMode.supportedBy(engine)) {
            add(
                ValidationError.TLS_MODE,
                "This engine supports " +
                    TlsMode.supportedBy(engine).joinToString(" and ") { it.wire } + ".",
            )
        }

        when (engine) {
            Engine.POSTGRES -> validatePostgresDatabase()
            Engine.REDIS -> validateRedisDatabase()
        }
    }

    private fun MutableList<ValidationError>.validateHost() {
        when {
            host.isEmpty() -> add(ValidationError.HOST, "A host is required.")
            host.length > MAX_HOST ->
                add(ValidationError.HOST, "A host may be at most $MAX_HOST characters.")
            host.contains("://") ->
                add(ValidationError.HOST, "Enter a host name or IP address without a URL scheme.")
            host.any { it in FORBIDDEN_IN_HOST } ->
                add(ValidationError.HOST, "Enter a host name or IP address only.")
            !host.isIpAddress() && !HOST.matches(host) ->
                add(ValidationError.HOST, "This is not a valid host name or IP address.")
        }
    }

    private fun MutableList<ValidationError>.validatePostgresDatabase() {
        when {
            database.isEmpty() -> add(ValidationError.DATABASE, "A database name is required.")
            database.length > MAX_DATABASE ->
                add(ValidationError.DATABASE, "A database name may be at most $MAX_DATABASE characters.")
        }
        // A username is usually required, but some servers authenticate by
        // certificate or peer identity, so its absence is not an error here.
    }

    private fun MutableList<ValidationError>.validateRedisDatabase() {
        val index = database.toIntOrNull()
        when {
            database.isEmpty() -> add(ValidationError.DATABASE, "A database index is required.")
            index == null ->
                add(ValidationError.DATABASE, "A Redis database must be an index such as 0.")
            index < 0 || index > MAX_REDIS_DB ->
                add(ValidationError.DATABASE, "A Redis database index must be between 0 and $MAX_REDIS_DB.")
        }
    }

    /**
     * Builds the stored config. The draft must already be [normalized] and valid;
     * the caller supplies the identity and creation time.
     */
    fun toConfig(id: ConnectionId, createdAt: Instant): ConnectionConfig = ConnectionConfig(
        id = id,
        name = name,
        engine = engine,
        host = host,
        port = port ?: engine.defaultPort,
        database = database,
        username = username,
        tlsMode = tlsMode ?: TlsMode.DEFAULT,
        environment = environment,
        readOnly = readOnly,
        color = color,
        createdAt = createdAt,
    )

    private fun MutableList<ValidationError>.add(field: String, message: String) {
        add(ValidationError(field, message))
    }

    companion object {
        const val MAX_NAME = 100
        const val MAX_HOST = 255
        const val MAX_USERNAME = 100
        const val MAX_DATABASE = 100

        /** Redis ships with databases 0..15. */
        const val MAX_REDIS_DB = 15

        private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$")
        private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
        private val FORBIDDEN_IN_HOST = charArrayOf(' ', '\t', '/', '@', '?', '#')

        /** Recognises a literal address without ever performing a DNS lookup. */
        private fun String.isIpAddress(): Boolean =
            // A name resolves through DNS; a literal does not. Checking the shape
            // first keeps InetAddress from touching the network.
            (contains(':') || matches(Regex("^[0-9.]+$"))) &&
                runCatching { InetAddress.ofLiteral(this) }.isSuccess

        /** Builds a draft that edits an existing connection, leaving its secret alone. */
        fun of(config: ConnectionConfig): ConnectionDraft = ConnectionDraft(
            name = config.name,
            engine = config.engine,
            host = config.host,
            port = config.port,
            database = config.database,
            username = config.username,
            tlsMode = config.tlsMode,
            environment = config.environment,
            readOnly = config.readOnly,
            color = config.color,
            secret = SecretUpdate.Unchanged,
        )
    }
}

/** One problem with a draft. [field] lets the form focus the offending input. */
data class ValidationError(val field: String, val message: String) {
    companion object {
        const val NAME = "name"
        const val HOST = "host"
        const val PORT = "port"
        const val DATABASE = "database"
        const val USERNAME = "username"
        const val TLS_MODE = "tlsMode"
        const val COLOR = "color"
        const val ENVIRONMENT = "environment"
    }
}

/** Thrown when a draft reaches the service without being valid. */
class ValidationException(val errors: List<ValidationError>) :
    Exception(errors.joinToString("; ") { "${it.field}: ${it.message}" })
