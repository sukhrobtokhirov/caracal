package dev.caracal.core.connections

import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.FormField
import dev.caracal.engine.api.FormKeys
import dev.caracal.engine.api.TlsConfig
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
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
 * A create or update request, in the shape the engine's form declared.
 *
 * [values] is every field of [DatabaseEngine.connectionForm] as the user left it,
 * keyed by [FormField.key] and held as text — because text is what a form holds, and
 * because "6d32" has to survive as far as the validator that can say it is not a
 * port. Everything that is *not* engine-declared stays a field here: the name, the
 * colour, the environment and the read-only flag are the application's own questions
 * and are asked of every engine identically.
 *
 * Validation is split the way §7 splits classification: the rules that follow from
 * the declaration — required, numeric, in range, one of these options, a file that
 * exists — are applied here for every engine at once, and everything that needs to
 * know what the engine *is* comes back from [DatabaseEngine.validate]. Neither half
 * knows the other's engines.
 */
data class ConnectionDraft(
    val engineId: EngineId,
    val name: String = "",
    val values: Map<String, String> = emptyMap(),
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
     * Trims, drops what this engine did not ask for, and fills in its declared
     * defaults. Runs before [validate], so the defaults are themselves validated
     * rather than trusted.
     *
     * Dropping the undeclared keys is what makes switching engines safe: a form that
     * carried `database = "payments"` over to an engine with no database field would
     * otherwise store a value nothing will ever read and nothing can ever edit.
     */
    fun normalized(engine: DatabaseEngine): ConnectionDraft = copy(
        name = name.trim(),
        color = color?.trim()?.ifEmpty { null },
        values = engine.fields.associate { field ->
            val typed = values[field.key]?.trim().orEmpty()
            field.key to typed.ifEmpty { field.unsetValue.orEmpty() }
        },
    )

    /**
     * Every problem with this draft, so the form can show them together rather than
     * one per round trip. An empty list means the draft is usable.
     *
     * At most one message per field: the declaration's own rule is reported in
     * preference to the engine's, because "Database is required" is the more useful
     * of the two things said about an empty box.
     */
    fun validate(engine: DatabaseEngine): List<ValidationError> {
        val found = buildList {
            when {
                name.isEmpty() -> add(ValidationError.NAME, "A name is required.")
                name.length > MAX_NAME -> add(ValidationError.NAME, "A name may be at most $MAX_NAME characters.")
            }
            if (color != null && !COLOR.matches(color)) {
                add(ValidationError.COLOR, "A color must be a hex value such as #4c8dff.")
            }
            engine.fields.forEach { field -> validateField(field, values[field.key]) }
            engine.validate(descriptor(ConnectionId(""), engine)).forEach { issue ->
                add(issue.field ?: ValidationError.FORM, issue.message)
            }
        }
        return found.distinctBy { it.field }
    }

    /**
     * Builds the stored config. The draft must already be [normalized] and valid;
     * the caller supplies the identity and creation time.
     */
    fun toConfig(engine: DatabaseEngine, id: ConnectionId, createdAt: Instant): ConnectionConfig =
        ConnectionConfig(
            id = id,
            name = name,
            engineId = engine.id,
            target = target(),
            settings = settings(engine),
            environment = environment,
            readOnly = readOnly,
            color = color,
            createdAt = createdAt,
        )

    /**
     * What this connection points at, read out of the declared fields.
     *
     * A declared file path wins over a host, because an engine that has both is
     * describing a server it can also reach through a socket file, and the path is
     * the more specific answer. An engine that declares neither gets an empty network
     * target and its own `validate` gets to say so.
     */
    private fun target(): ConnectionTarget {
        val path = values[FormKeys.PATH].orEmpty()
        if (path.isNotEmpty()) {
            val parsed = runCatching { Path.of(path) }.getOrNull()
            if (parsed != null) return ConnectionTarget.File(parsed)
        }
        return ConnectionTarget.Network(
            host = values[FormKeys.HOST].orEmpty(),
            port = values[FormKeys.PORT]?.toIntOrNull() ?: 0,
            database = values[FormKeys.DATABASE],
        )
    }

    /**
     * The declared fields that are not the target and not a secret.
     *
     * The target keys are left out because [ConnectionConfig.target] already holds
     * them, and two copies of a host is one copy too many. A [FormField.Secret] is
     * left out because its value belongs in the vault, and a settings map is stored
     * in the clear.
     */
    private fun settings(engine: DatabaseEngine): Map<String, String> =
        engine.fields
            .filterNot { it is FormField.Secret || it.key in TARGET_KEYS }
            .mapNotNull { field -> values[field.key]?.takeIf { it.isNotEmpty() }?.let { field.key to it } }
            .toMap()

    /**
     * This draft as the engine sees it, for the sake of [DatabaseEngine.validate].
     *
     * It carries no identity and no secret: an engine validating a form is judging
     * the settings, and giving it a real id would invite it to go looking for one.
     */
    private fun descriptor(id: ConnectionId, engine: DatabaseEngine): ConnectionDescriptor =
        ConnectionDescriptor(
            id = id,
            engineId = engine.id,
            displayName = name,
            target = target(),
            environment = environment,
            writable = !readOnly,
            tls = when (TlsMode.from(values[FormKeys.TLS])) {
                null, TlsMode.DISABLE -> TlsConfig.Disabled
                // The distinction `require` draws is the engine's own, and the
                // descriptor mapping in `registry/Descriptors.kt` is where it is
                // drawn. Here the question is only whether the user asked for TLS,
                // so the strictest reading is the safe one to validate against.
                TlsMode.REQUIRE -> TlsConfig.Required(verifyHostname = !engine.offersUnverifiedTls)
                TlsMode.VERIFY_FULL -> TlsConfig.Required(verifyHostname = true)
            },
            engineOptions = values.filterKeys { it != FormKeys.PASSWORD },
        )

    private fun MutableList<ValidationError>.validateField(field: FormField, raw: String?) {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) {
            if (field.required) add(field.key, "${field.label} is required.")
            return
        }
        if (value.length > MAX_VALUE) {
            add(field.key, "${field.label} may be at most $MAX_VALUE characters.")
            return
        }
        when (field) {
            is FormField.Number -> validateNumber(field, value)
            is FormField.Choice -> validateChoice(field, value)
            is FormField.FilePath -> validateFile(field, value)
            else -> Unit
        }
        // Core knows what a host is, and it is the same thing for every engine that
        // dials one: a name or an address, never a URL and never something with a
        // password in front of it. The check is keyed on the well-known key rather
        // than on the engine, so an engine that declares a host gets it for free.
        if (field.key == FormKeys.HOST) validateHost(value)
    }

    private fun MutableList<ValidationError>.validateNumber(field: FormField.Number, value: String) {
        val number = value.toIntOrNull()
        if (number == null) {
            add(field.key, "${field.label} must be a number.")
            return
        }
        val range = field.range ?: return
        if (number !in range) {
            add(field.key, "${field.label} must be between ${range.first} and ${range.last}.")
        }
    }

    private fun MutableList<ValidationError>.validateChoice(field: FormField.Choice, value: String) {
        if (field.options.isEmpty() || field.options.any { it.first == value }) return
        add(
            field.key,
            "${field.label} must be one of " + field.options.joinToString(", ") { it.first } + ".",
        )
    }

    private fun MutableList<ValidationError>.validateFile(field: FormField.FilePath, value: String) {
        val path = runCatching { Path.of(value) }.getOrElse {
            if (it is InvalidPathException) {
                add(field.key, "${field.label} is not a valid path.")
                return
            }
            throw it
        }
        if (field.mustExist && !Files.isReadable(path)) {
            add(field.key, "${field.label} does not name a readable file.")
        }
    }

    private fun MutableList<ValidationError>.validateHost(host: String) {
        when {
            host.length > MAX_HOST ->
                add(FormKeys.HOST, "A host may be at most $MAX_HOST characters.")
            host.contains("://") ->
                add(FormKeys.HOST, "Enter a host name or IP address without a URL scheme.")
            host.any { it in FORBIDDEN_IN_HOST } ->
                add(FormKeys.HOST, "Enter a host name or IP address only.")
            !host.isIpAddress() && !HOST.matches(host) ->
                add(FormKeys.HOST, "This is not a valid host name or IP address.")
        }
    }

    private fun MutableList<ValidationError>.add(field: String, message: String) {
        add(ValidationError(field, message))
    }

    companion object {
        const val MAX_NAME = 100

        /**
         * A cap on any declared value, as a backstop rather than as a rule about
         * anything. An engine's real limits are its own to state — PostgreSQL's
         * database names and Redis's ACL users are both shorter than this — and what
         * this stops is a hand-edited store or a paste of a whole file into a field.
         */
        const val MAX_VALUE = 255

        const val MAX_HOST = 255

        /** The declared keys that become [ConnectionConfig.target] rather than a setting. */
        private val TARGET_KEYS = setOf(FormKeys.HOST, FormKeys.PORT, FormKeys.DATABASE, FormKeys.PATH)

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
            engineId = config.engineId,
            name = config.name,
            values = buildMap {
                putAll(config.settings)
                when (val target = config.target) {
                    is ConnectionTarget.Network -> {
                        put(FormKeys.HOST, target.host)
                        put(FormKeys.PORT, target.port.toString())
                        target.database?.let { put(FormKeys.DATABASE, it) }
                    }

                    is ConnectionTarget.File -> put(FormKeys.PATH, target.path.toString())
                    else -> Unit
                }
            },
            environment = config.environment,
            readOnly = config.readOnly,
            color = config.color,
            secret = SecretUpdate.Unchanged,
        )
    }
}

/** Every field of every section, which is the order the form is read in. */
val DatabaseEngine.fields: List<FormField> get() = connectionForm.sections.flatMap { it.fields }

/**
 * Whether this engine's `require` means encrypt-without-checking.
 *
 * Read off the declaration rather than off the engine's name: an engine that offers
 * both `require` and `verify-full` is drawing the distinction PostgreSQL draws, so
 * its `require` is the weaker of the two. An engine that offers `require` alone is
 * saying that its one secure mode is that word — which is what Redis says, because
 * its client has always verified.
 */
val DatabaseEngine.offersUnverifiedTls: Boolean
    get() = tlsModes.containsAll(listOf(TlsMode.REQUIRE, TlsMode.VERIFY_FULL))

/** The transport modes this engine's form offers, in the order it offers them. */
val DatabaseEngine.tlsModes: List<TlsMode>
    get() = (fields.firstOrNull { it.key == FormKeys.TLS } as? FormField.Choice)
        ?.options
        ?.mapNotNull { TlsMode.from(it.first) }
        .orEmpty()

/**
 * What an empty field means, which is not the same thing as what a form prefills.
 *
 * A number, a choice and a toggle have a value when nothing has been typed: an empty
 * port box means the engine's port, and an unticked box is false. A text field does
 * not — an empty host is an empty host, and quietly reading it as `localhost` because
 * that is what the form suggests would save a connection to a server the user
 * deleted the name of. So a text field's declared default is a suggestion the form
 * starts with and this ignores.
 */
private val FormField.unsetValue: String?
    get() = when (this) {
        is FormField.Number -> default?.toString()
        is FormField.Choice -> default
        is FormField.Toggle -> default.toString()
        is FormField.Text, is FormField.FilePath, is FormField.Secret -> null
    }

/**
 * One problem with a draft. [field] is a [FormField.key], so the form can focus the
 * input that caused it, or [FORM] for an objection about the whole thing.
 */
data class ValidationError(val field: String, val message: String) {
    companion object {
        const val NAME = "name"
        const val COLOR = "color"
        const val ENVIRONMENT = "environment"

        /** An engine's objection to the combination rather than to one field. */
        const val FORM = ""
    }
}

/** Thrown when a draft reaches the service without being valid. */
class ValidationException(val errors: List<ValidationError>) :
    Exception(errors.joinToString("; ") { "${it.field}: ${it.message}" })
