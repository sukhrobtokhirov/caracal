package dev.dbide.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.dbide.app.ConnectionFormState
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.connections.ValidationError

/**
 * The create and edit form.
 *
 * Two rules matter more than the layout. A saved password is never prefilled and
 * never shown — the form says it is being left alone until the user decides
 * otherwise. And the non-secret fields survive a failed save, so a wrong password
 * does not cost the user everything else they typed.
 */
@Composable
fun ConnectionForm(
    form: ConnectionFormState,
    busy: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequesters = remember { ValidationFields.associateWith { FocusRequester() } }

    // A rejected save should put the cursor where the problem is.
    LaunchedEffect(form.errors) {
        form.firstInvalidField?.let { field -> focusRequesters[field]?.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        Text(
            if (form.isEditing) "Edit connection" else "New connection",
            style = MaterialTheme.typography.titleMedium,
        )

        Field(
            label = "Name",
            value = form.name,
            onChange = form::onName,
            error = form.errors[ValidationError.NAME],
            enabled = !busy,
            focusRequester = focusRequesters.getValue(ValidationError.NAME),
            description = "field-name",
        )

        ChipRow("Engine") {
            Engine.entries.forEach { engine ->
                FilterChip(
                    selected = form.engine == engine,
                    onClick = { form.onEngine(engine) },
                    enabled = !busy,
                    label = { Text(engine.wire) },
                    modifier = Modifier.semantics { contentDescription = "engine-choice-${engine.wire}" },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
            Field(
                label = "Host",
                value = form.host,
                onChange = form::onHost,
                error = form.errors[ValidationError.HOST],
                enabled = !busy,
                focusRequester = focusRequesters.getValue(ValidationError.HOST),
                description = "field-host",
                modifier = Modifier.weight(1f),
            )
            Field(
                label = "Port",
                value = form.port,
                onChange = form::onPort,
                error = form.errors[ValidationError.PORT],
                enabled = !busy,
                focusRequester = focusRequesters.getValue(ValidationError.PORT),
                description = "field-port",
                placeholder = form.engine.defaultPort.toString(),
                modifier = Modifier.width(140.dp),
            )
        }

        Field(
            label = if (form.engine == Engine.REDIS) "Database index" else "Database",
            value = form.database,
            onChange = form::onDatabase,
            error = form.errors[ValidationError.DATABASE],
            enabled = !busy,
            focusRequester = focusRequesters.getValue(ValidationError.DATABASE),
            description = "field-database",
        )

        Field(
            label = if (form.engine == Engine.REDIS) "Username (optional)" else "Username",
            value = form.username,
            onChange = form::onUsername,
            error = form.errors[ValidationError.USERNAME],
            enabled = !busy,
            focusRequester = focusRequesters.getValue(ValidationError.USERNAME),
            description = "field-username",
        )

        SecretField(form, busy)

        ChipRow("TLS") {
            form.availableTlsModes.forEach { mode ->
                FilterChip(
                    selected = form.tlsMode == mode,
                    onClick = { form.onTlsMode(mode) },
                    enabled = !busy,
                    label = { Text(mode.wire) },
                    modifier = Modifier.semantics { contentDescription = "tls-choice-${mode.wire}" },
                )
            }
        }
        form.errors[ValidationError.TLS_MODE]?.let { FieldError(it) }

        ChipRow("Environment") {
            Environment.entries.sortedBy { -it.severity }.forEach { environment ->
                FilterChip(
                    selected = form.environment == environment,
                    onClick = { form.onEnvironment(environment) },
                    enabled = !busy,
                    label = { Text(environment.wire) },
                    modifier = Modifier.semantics {
                        contentDescription = "environment-choice-${environment.wire}"
                    },
                )
            }
        }

        // Chosen before saving, and shown everywhere afterwards.
        if (form.environment == Environment.PROD) {
            Text(
                "This connection will be marked PROD everywhere it appears.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "prod-warning" },
            )
        }

        CheckboxRow(
            checked = form.readOnly,
            onChange = form::onReadOnly,
            enabled = !busy,
            label = "Read only",
            description = "field-read-only",
        )

        Field(
            label = "Color (optional)",
            value = form.color,
            onChange = form::onColor,
            error = form.errors[ValidationError.COLOR],
            enabled = !busy,
            focusRequester = focusRequesters.getValue(ValidationError.COLOR),
            description = "field-color",
            placeholder = "#4c8dff",
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                shape = MaterialTheme.shapes.small,
                onClick = onSave,
                // Disabled while saving: this is what stops a double click from
                // creating the connection twice.
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "save-connection" },
            ) {
                Text(if (form.isEditing) "Save changes" else "Create connection")
            }
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "cancel-form" },
            ) {
                Text("Cancel")
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * The password field, and the promise attached to it.
 *
 * On an edit this starts as a statement rather than an input: the saved credential
 * is left alone unless the user opts in. Opting in and typing nothing is how a
 * password is removed, which the hint says out loud.
 */
@Composable
private fun SecretField(form: ConnectionFormState, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        if (form.isEditing) {
            CheckboxRow(
                checked = form.replaceSecret,
                onChange = form::onReplaceSecret,
                enabled = !busy,
                label = if (form.replaceSecret) {
                    "Replace the saved password"
                } else {
                    "Leave saved password unchanged"
                },
                description = "replace-password",
            )
            if (!form.replaceSecret && !form.hasStoredSecret) {
                Text(
                    "No password is saved for this connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (form.replaceSecret) {
            OutlinedTextField(
                value = form.secret,
                onValueChange = form::onSecret,
                label = { Text("Password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "field-password" },
            )
            if (form.isEditing) {
                Text(
                    "Leave this empty to remove the saved password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    enabled: Boolean,
    focusRequester: FocusRequester,
    description: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            isError = error != null,
            placeholder = placeholder?.let { { Text(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .semantics { contentDescription = description },
        )
        error?.let { FieldError(it) }
    }
}

/**
 * A checkbox and its label as one control. The whole row toggles, which is both a
 * larger hit target and the thing a screen reader announces as a single choice.
 */
@Composable
private fun CheckboxRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean,
    label: String,
    description: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .semantics { contentDescription = description },
    ) {
        // The row owns the click, so the box itself must not also handle it.
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = Space.md),
        )
    }
}

@Composable
private fun FieldError(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { contentDescription = "field-error" },
    )
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            modifier = Modifier.selectableGroup(),
        ) {
            content()
        }
    }
}

/** The fields that can hold focus, so every validation error has somewhere to land. */
private val ValidationFields = listOf(
    ValidationError.NAME,
    ValidationError.HOST,
    ValidationError.PORT,
    ValidationError.DATABASE,
    ValidationError.USERNAME,
    ValidationError.COLOR,
)
