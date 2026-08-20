package dev.dbide.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dbide.core.connections.ConnectionDraft
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.Secret
import dev.dbide.core.connections.SecretUpdate
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.connections.ValidationError

/**
 * The connection form's fields, as the user types them.
 *
 * Everything is text here because that is what a text field holds; the conversion
 * to a [ConnectionDraft] is where a port becomes a number and can fail. Validation
 * itself stays in `:core` — this class only adds the one rule `:core` cannot see,
 * which is that "6d32" is not a port at all.
 */
class ConnectionFormState(
    /** The connection being edited, or null when creating a new one. */
    val editing: ConnectionView? = null,
) {
    var name: String by mutableStateOf(editing?.config?.name ?: "")
        private set

    var engine: Engine by mutableStateOf(editing?.config?.engine ?: Engine.POSTGRES)
        private set

    var host: String by mutableStateOf(editing?.config?.host ?: "")
        private set

    var port: String by mutableStateOf(editing?.config?.port?.toString() ?: "")
        private set

    var database: String by mutableStateOf(editing?.config?.database ?: "")
        private set

    var username: String by mutableStateOf(editing?.config?.username ?: "")
        private set

    var tlsMode: TlsMode by mutableStateOf(editing?.config?.tlsMode ?: TlsMode.DISABLE)
        private set

    var environment: Environment by mutableStateOf(editing?.config?.environment ?: Environment.DEV)
        private set

    var readOnly: Boolean by mutableStateOf(editing?.config?.readOnly ?: true)
        private set

    var color: String by mutableStateOf(editing?.config?.color ?: "")
        private set

    var secret: String by mutableStateOf("")
        private set

    /**
     * On an edit the saved password is never prefilled and never shown. Until the
     * user turns this on, the form says so and the stored credential is untouched.
     */
    var replaceSecret: Boolean by mutableStateOf(editing == null)
        private set

    /** Per-field messages, keyed by [ValidationError]'s field names. */
    var errors: Map<String, String> by mutableStateOf(emptyMap())
        private set

    /** The field the form should focus, which is the first one with a problem. */
    val firstInvalidField: String? get() = FIELD_ORDER.firstOrNull { it in errors }

    val isEditing: Boolean get() = editing != null

    val id: ConnectionId? get() = editing?.id

    /** Whether this connection already has a stored password. */
    val hasStoredSecret: Boolean get() = editing?.hasSecret == true

    val availableTlsModes: List<TlsMode> get() = TlsMode.supportedBy(engine)

    fun onName(value: String) = edit { name = value }

    fun onHost(value: String) = edit { host = value }

    fun onPort(value: String) = edit { port = value.filter { it.isDigit() } }

    fun onDatabase(value: String) = edit { database = value }

    fun onUsername(value: String) = edit { username = value }

    fun onColor(value: String) = edit { color = value }

    fun onSecret(value: String) = edit { secret = value }

    fun onReadOnly(value: Boolean) = edit { readOnly = value }

    fun onEnvironment(value: Environment) = edit { environment = value }

    fun onTlsMode(value: TlsMode) = edit { tlsMode = value }

    fun onReplaceSecret(value: Boolean) = edit {
        replaceSecret = value
        if (!value) secret = ""
    }

    /**
     * Switching engines carries over what still applies and resets what does not:
     * the port and TLS mode are engine-specific, and a PostgreSQL database name is
     * not a Redis index.
     */
    fun onEngine(value: Engine) = edit {
        if (value == engine) return@edit
        val wasDefaultPort = port.isEmpty() || port.toIntOrNull() == engine.defaultPort
        engine = value
        if (wasDefaultPort) port = value.defaultPort.toString()
        if (tlsMode !in TlsMode.supportedBy(value)) tlsMode = TlsMode.DISABLE
        database = if (value == Engine.REDIS) "0" else ""
    }

    /**
     * The draft `:core` should act on, or null when the form has a problem `:core`
     * would never see. Either way [errors] is refreshed.
     */
    fun toDraft(): ConnectionDraft? {
        val typedPort = port.trim()
        if (typedPort.isNotEmpty() && typedPort.toIntOrNull() == null) {
            errors = mapOf(ValidationError.PORT to "A port must be a number.")
            return null
        }

        val draft = ConnectionDraft(
            name = name,
            engine = engine,
            // An empty port field means "use the engine default", which is what a
            // null port means to `:core`.
            port = typedPort.toIntOrNull(),
            host = host,
            database = database,
            username = username,
            tlsMode = tlsMode,
            environment = environment,
            readOnly = readOnly,
            color = color.ifBlank { null },
            secret = secretUpdate(),
        ).normalized()

        errors = draft.validate().associate { it.field to it.message }
        return if (errors.isEmpty()) draft else null
    }

    /** Records failures the service reported that the form itself did not catch. */
    fun showErrors(fields: List<ValidationError>) {
        if (fields.isNotEmpty()) errors = fields.associate { it.field to it.message }
    }

    private fun secretUpdate(): SecretUpdate = when {
        !replaceSecret -> SecretUpdate.Unchanged
        // The user chose to replace the password and typed nothing: that is a
        // deliberate removal, not an accident an empty field could cause on its own.
        secret.isEmpty() -> SecretUpdate.Clear
        else -> SecretUpdate.Replace(Secret(secret))
    }

    /** Any edit clears the field's stale message, so it does not outlive the problem. */
    private inline fun edit(block: () -> Unit) {
        block()
        if (errors.isNotEmpty()) errors = emptyMap()
    }

    companion object {
        /** Focus order, so the first invalid field is the topmost one. */
        private val FIELD_ORDER = listOf(
            ValidationError.NAME,
            ValidationError.HOST,
            ValidationError.PORT,
            ValidationError.DATABASE,
            ValidationError.USERNAME,
            ValidationError.TLS_MODE,
            ValidationError.ENVIRONMENT,
            ValidationError.COLOR,
        )
    }
}
