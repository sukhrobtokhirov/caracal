package dev.caracal.engine.api

/**
 * The connection dialog, described rather than drawn.
 *
 * The alternative is the one the repository already has in miniature: a dialog that
 * grows a `when (engine)` for the database field, another for the TLS modes, another
 * for the port default. Three engines in, that dialog is where every engine's
 * peculiarities meet, and it is in `:ui`, which is the one module that must not know
 * any of them.
 *
 * So each engine declares its form, the dialog renders it generically, and hands
 * back a `Map<String, String>` keyed by [FormField.key] for
 * [DatabaseEngine.validate] to judge. The messages come back from the engine too,
 * which is what makes "port must be between 1 and 65535" and "that database file is
 * not readable" both first-class instead of one being generic and the other being
 * impossible.
 */
data class ConnectionForm(val sections: List<FormSection>)

data class FormSection(val title: String, val fields: List<FormField>)

sealed interface FormField {
    /** The key this field's value is returned under. Stable; it is what code reads. */
    val key: String
    val label: String
    val required: Boolean
    val help: String?

    data class Text(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val default: String? = null,
    ) : FormField

    data class Number(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val default: Int? = null,
        val range: IntRange? = null,
    ) : FormField

    data class FilePath(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val extensions: List<String> = emptyList(),
        val mustExist: Boolean = true,
    ) : FormField

    /** [options] is value to label, in the order they should be offered. */
    data class Choice(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val options: List<Pair<String, String>> = emptyList(),
        val default: String? = null,
    ) : FormField

    data class Toggle(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val default: Boolean = false,
    ) : FormField

    data class Secret(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val kind: SecretKind = SecretKind.PASSWORD,
    ) : FormField
}

/**
 * Something wrong with a connection's settings, said by the engine that knows.
 *
 * [field] is a [FormField.key] when the objection is about one field, and null when
 * it is about the combination — a TLS mode this engine does not offer, a file path
 * given for an engine that dials a host.
 */
data class ValidationIssue(
    val message: String,
    val field: String? = null,
    val severity: IssueSeverity = IssueSeverity.ERROR,
)

enum class IssueSeverity { ERROR, WARNING }
