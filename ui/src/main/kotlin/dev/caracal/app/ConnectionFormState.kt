package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.caracal.core.connections.ConnectionDraft
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionView
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.SecretUpdate
import dev.caracal.core.connections.ValidationError
import dev.caracal.core.connections.fields
import dev.caracal.core.engines.Engines
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.FormField

/**
 * Asks the user for a file, for the one declared field kind that names one.
 *
 * Injected rather than called, for the reason `FileChooser` is: a native open dialog
 * is a modal window parented to the application's own, so as a parameter it is an AWT
 * `FileDialog` in the application and a function returning a fixed path in a test —
 * which is the difference between the browse button being verified and being verified
 * by hand.
 *
 * Null where there is nowhere to parent a dialog to. The field is still typeable; what
 * disappears is the button, rather than a button that does nothing when pressed.
 */
typealias FilePicker = suspend (field: FormField.FilePath) -> java.nio.file.Path?

/**
 * The connection form's fields, as the user types them.
 *
 * Everything is text here because that is what a text field holds. What is no longer
 * here is a list of *which* fields there are: the engine declares them, this class
 * holds whatever it declared under the engine's own keys, and the dialog draws them.
 * A form that names a host and a port cannot describe an engine that opens a file,
 * and enumerating the fields in three places — a state class, a dialog, and a draft —
 * is how a new engine turns into a week of edits.
 *
 * Validation stays in `:core`, which asks the engine. This class adds the one rule
 * the engine cannot see, which is that a number field must hold digits.
 */
class ConnectionFormState(
    /** The engines to offer. Every one on the classpath, unless a test says otherwise. */
    val engines: List<DatabaseEngine> = Engines.all,
    /** The connection being edited, or null when creating a new one. */
    val editing: ConnectionView? = null,
) {
    /**
     * The chosen engine.
     *
     * An edit of a connection whose engine this build does not have falls back to the
     * first offered one, because the alternative is a dialog with no fields at all.
     * The connection list refuses to open such a connection long before this.
     */
    var engine: DatabaseEngine by mutableStateOf(
        editing?.config?.engineId?.let { Engines.byId(it) } ?: engines.first(),
    )
        private set

    var name: String by mutableStateOf(editing?.config?.name ?: "")
        private set

    var color: String by mutableStateOf(editing?.config?.color ?: "")
        private set

    var environment: Environment by mutableStateOf(editing?.config?.environment ?: Environment.DEV)
        private set

    var readOnly: Boolean by mutableStateOf(editing?.config?.readOnly ?: true)
        private set

    var secret: String by mutableStateOf("")
        private set

    /** Every declared field's value, keyed by [FormField.key]. */
    private var values: Map<String, String> by mutableStateOf(
        editing?.config?.let { ConnectionDraft.of(it).values } ?: defaults(engine),
    )

    /**
     * On an edit the saved password is never prefilled and never shown. Until the
     * user turns this on, the form says so and the stored credential is untouched.
     */
    var replaceSecret: Boolean by mutableStateOf(editing == null)
        private set

    /** Per-field messages, keyed by [FormField.key] and by [ValidationError]'s own names. */
    var errors: Map<String, String> by mutableStateOf(emptyMap())
        private set

    /** The fields to draw, in the order the engine declared them. */
    val sections get() = engine.connectionForm.sections

    /** The field the form should focus, which is the first one with a problem. */
    val firstInvalidField: String? get() = focusOrder.firstOrNull { it in errors }

    val isEditing: Boolean get() = editing != null

    val id: ConnectionId? get() = editing?.id

    /** Whether this connection already has a stored password. */
    val hasStoredSecret: Boolean get() = editing?.hasSecret == true

    /** The engine's password field, if it declares one. */
    val secretField: FormField.Secret?
        get() = engine.fields.filterIsInstance<FormField.Secret>().firstOrNull()

    fun value(key: String): String = values[key].orEmpty()

    fun onValue(field: FormField, raw: String) = edit {
        // The one rule the declaration implies and a text field does not enforce: a
        // number field that can hold letters is a field whose error message has to
        // explain itself later.
        val typed = if (field is FormField.Number) raw.filter { it.isDigit() } else raw
        values = values + (field.key to typed)
    }

    fun onName(value: String) = edit { name = value }

    fun onColor(value: String) = edit { color = value }

    fun onSecret(value: String) = edit { secret = value }

    fun onReadOnly(value: Boolean) = edit { readOnly = value }

    fun onEnvironment(value: Environment) = edit { environment = value }

    fun onReplaceSecret(value: Boolean) = edit {
        replaceSecret = value
        if (!value) secret = ""
    }

    /**
     * Switching engines carries over what still applies and resets what does not.
     *
     * "Still applies" is decided by the two declarations rather than by a list here: a
     * value the user typed is kept when the new engine has a field of that name, and a
     * value that was only ever the old engine's default is replaced by the new one's.
     * That is what makes the port follow the engine while a deliberately typed 6432
     * survives — the rule the old `when` expressed for two engines, said once.
     */
    fun onEngine(next: DatabaseEngine) = edit {
        if (next.id == engine.id) return@edit
        val previous = engine
        engine = next
        values = next.fields.associate { field ->
            val carried = values[field.key].orEmpty()
            val default = field.declaredDefault.orEmpty()
            val stale = carried.isEmpty() ||
                carried == previous.declaredDefault(field.key) ||
                !field.accepts(carried)
            field.key to if (stale) default else carried
        }
    }

    /**
     * The draft `:core` should act on, or null when the form has a problem `:core`
     * would never see. Either way [errors] is refreshed.
     */
    fun toDraft(): ConnectionDraft? {
        val draft = ConnectionDraft(
            engineId = engine.id,
            name = name,
            values = values,
            environment = environment,
            readOnly = readOnly,
            color = color.ifBlank { null },
            secret = secretUpdate(),
        ).normalized(engine)

        errors = draft.validate(engine).associate { it.field to it.message }
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

    /** Focus order, so the first invalid field is the topmost one. */
    private val focusOrder: List<String>
        get() = listOf(ValidationError.NAME) +
            engine.fields.map { it.key } +
            listOf(ValidationError.COLOR, ValidationError.ENVIRONMENT, ValidationError.FORM)

    /** Any edit clears the field's stale message, so it does not outlive the problem. */
    private inline fun edit(block: () -> Unit) {
        block()
        if (errors.isNotEmpty()) errors = emptyMap()
    }

    private fun defaults(engine: DatabaseEngine): Map<String, String> =
        engine.fields.associate { it.key to it.declaredDefault.orEmpty() }

    private fun DatabaseEngine.declaredDefault(key: String): String? =
        fields.firstOrNull { it.key == key }?.declaredDefault
}

/** The value a field starts at when nothing has been typed into it. */
internal val FormField.declaredDefault: String?
    get() = when (this) {
        is FormField.Text -> default
        is FormField.Number -> default?.toString()
        is FormField.Choice -> default
        is FormField.Toggle -> default.toString()
        is FormField.FilePath, is FormField.Secret -> null
    }

/** Whether a value carried over from another engine is one this field could hold. */
private fun FormField.accepts(value: String): Boolean = when (this) {
    is FormField.Choice -> options.isEmpty() || options.any { it.first == value }
    is FormField.Number -> value.toIntOrNull()?.let { number -> range?.contains(number) ?: true } == true
    else -> true
}
