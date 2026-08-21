package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dbide.app.ConnectionFormState
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.connections.ValidationError

/**
 * Adding or editing a connection, as a window rather than a pane.
 *
 * The engine is the first decision and it now looks like one. It used to be a row of
 * two chips halfway down a form, which is the same weight the form gave to the TLS
 * mode — and it is not the same kind of question: choosing PostgreSQL or Redis
 * changes which fields below mean anything, what the default port is, and which
 * transport modes exist. So it moved to a rail on the left, one entry per engine,
 * each with its own mark, and the form for the chosen one is on the right of the
 * same window. Selecting and filling in are one motion and one dialog.
 *
 * Two rules from the pane this replaced survive unchanged, because they are the two
 * that matter. A saved password is never prefilled and never shown — the dialog says
 * it is being left alone until the user decides otherwise. And the non-secret fields
 * survive a failed save, so a wrong password does not cost the user everything else
 * they typed.
 */
@Composable
fun ConnectionDialog(
    form: ConnectionFormState,
    busy: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequesters = remember { ValidationFields.associateWith { FocusRequester() } }

    // A rejected save should put the cursor where the problem is.
    LaunchedEffect(form.errors) {
        form.firstInvalidField?.let { field -> focusRequesters[field]?.requestFocus() }
    }

    AppDialog(
        title = if (form.isEditing) "Edit connection" else "New connection",
        subtitle = if (form.isEditing) {
            "Changes apply the next time this connection is opened."
        } else {
            "Everything stays on this machine. The password is sealed in the local vault."
        },
        tag = if (form.isEditing) "edit-connection-dialog" else "new-connection-dialog",
        icon = { EngineTile(form.engine, size = 36.dp, selected = true) },
        onDismiss = onCancel,
        // A half-typed connection is not something to lose to a stray click.
        dismissOnClickOutside = false,
        rail = { EngineRail(form = form, busy = busy) },
        footer = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier.testTag("cancel-form"),
            ) {
                Text("Cancel")
            }
            Button(
                shape = MaterialTheme.shapes.small,
                onClick = onSave,
                // Disabled while saving: this is what stops a double click from
                // creating the connection twice.
                enabled = !busy,
                modifier = Modifier.testTag("save-connection"),
            ) {
                Text(if (form.isEditing) "Save changes" else "Create connection")
            }
        },
    ) {
        // Paired across the window rather than stacked down it. A field per line is
        // the right shape for a phone and the wrong one for an 880-pixel dialog: it
        // put the environment chips — the choice that decides whether this connection
        // can be written to — below the fold on a laptop.
        DialogSection(title = "Identity", glyph = Glyphs.CONNECTIONS) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                Field(
                    label = "Name",
                    value = form.name,
                    onChange = form::onName,
                    error = form.errors[ValidationError.NAME],
                    enabled = !busy,
                    focusRequester = focusRequesters.getValue(ValidationError.NAME),
                    tag = "field-name",
                    placeholder = "payments-prod",
                    modifier = Modifier.weight(1f),
                )
                Field(
                    label = "Color (optional)",
                    value = form.color,
                    onChange = form::onColor,
                    error = form.errors[ValidationError.COLOR],
                    enabled = !busy,
                    focusRequester = focusRequesters.getValue(ValidationError.COLOR),
                    tag = "field-color",
                    placeholder = "#4c8dff",
                    modifier = Modifier.width(180.dp),
                )
            }
        }

        DialogSection(title = "Server", glyph = Glyphs.of(form.engine)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                Field(
                    label = "Host",
                    value = form.host,
                    onChange = form::onHost,
                    error = form.errors[ValidationError.HOST],
                    enabled = !busy,
                    focusRequester = focusRequesters.getValue(ValidationError.HOST),
                    tag = "field-host",
                    placeholder = "localhost",
                    modifier = Modifier.weight(1f),
                )
                Field(
                    label = "Port",
                    value = form.port,
                    onChange = form::onPort,
                    error = form.errors[ValidationError.PORT],
                    enabled = !busy,
                    focusRequester = focusRequesters.getValue(ValidationError.PORT),
                    tag = "field-port",
                    placeholder = form.engine.defaultPort.toString(),
                    modifier = Modifier.width(140.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                Field(
                    label = if (form.engine == Engine.REDIS) "Database index" else "Database",
                    value = form.database,
                    onChange = form::onDatabase,
                    error = form.errors[ValidationError.DATABASE],
                    enabled = !busy,
                    focusRequester = focusRequesters.getValue(ValidationError.DATABASE),
                    tag = "field-database",
                    modifier = Modifier.weight(1f),
                )
                Field(
                    label = if (form.engine == Engine.REDIS) "Username (optional)" else "Username",
                    value = form.username,
                    onChange = form::onUsername,
                    error = form.errors[ValidationError.USERNAME],
                    enabled = !busy,
                    focusRequester = focusRequesters.getValue(ValidationError.USERNAME),
                    tag = "field-username",
                    modifier = Modifier.weight(1f),
                )
            }

            SecretField(form, busy)

            ChipRow("TLS") {
                form.availableTlsModes.forEach { mode ->
                    FilterChip(
                        selected = form.tlsMode == mode,
                        onClick = { form.onTlsMode(mode) },
                        enabled = !busy,
                        label = { Text(mode.wire) },
                        modifier = Modifier.testTag("tls-choice-${mode.wire}"),
                    )
                }
            }
            form.errors[ValidationError.TLS_MODE]?.let { FieldError(it) }
        }

        DialogSection(
            title = "Safety",
            glyph = Glyphs.of(form.environment),
            detail = "How this connection is labelled everywhere it appears, and what it will let you run.",
        ) {
            ChipRow("Environment") {
                Environment.entries.sortedBy { -it.severity }.forEach { environment ->
                    FilterChip(
                        selected = form.environment == environment,
                        onClick = { form.onEnvironment(environment) },
                        enabled = !busy,
                        label = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Glyph(Glyphs.of(environment))
                                Text(environment.wire)
                            }
                        },
                        modifier = Modifier.semantics {
                            testTag = "environment-choice-${environment.wire}"
                        },
                    )
                }
            }

            // Chosen before saving, and shown everywhere afterwards.
            if (form.environment == Environment.PROD) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(Space.lg)
                        .testTag("prod-warning"),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(Glyphs.PROD, size = 13)
                    Text(
                        "This connection will be marked PROD everywhere it appears.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            CheckboxRow(
                checked = form.readOnly,
                onChange = form::onReadOnly,
                enabled = !busy,
                label = "Read only",
                tag = "field-read-only",
                glyph = Glyphs.READ_ONLY,
            )
        }
    }
}

/**
 * The rail: which engine this is, and what the dialog is about to create.
 *
 * The preview underneath is not decoration. A connection is defined by four fields
 * spread down a scroll, and the thing the user actually has to get right — the
 * server this will point at, and how dangerous it is — is only visible by reading
 * three of them together. The preview reads them together, in the same layout the
 * sidebar will show the saved connection in.
 */
@Composable
private fun EngineRail(form: ConnectionFormState, busy: Boolean) {
    RailHeading("Engine")
    Engine.entries.forEach { engine ->
        RailItem(
            label = engine.title,
            detail = engine.blurb,
            tag = "engine-choice-${engine.wire}",
            selected = form.engine == engine,
            enabled = !busy,
            onClick = { form.onEngine(engine) },
            leading = { EngineTile(engine, size = 30.dp, selected = form.engine == engine) },
        )
    }

    RailHeading("Preview")
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.md),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(Space.md)
            .testTag("connection-preview"),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EngineLogo(form.engine, size = 14.dp)
            Text(
                text = form.name.ifBlank { "Unnamed" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "${form.host.ifBlank { "host" }}:${form.port.ifBlank { form.engine.defaultPort.toString() }}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EnvironmentBadge(form.environment)
            if (form.readOnly) ReadOnlyBadge()
        }
    }
}

/** What the rail calls an engine, and the one line under it that says why. */
private val Engine.title: String
    get() = when (this) {
        Engine.POSTGRES -> "PostgreSQL"
        Engine.REDIS -> "Redis"
    }

private val Engine.blurb: String
    get() = when (this) {
        Engine.POSTGRES -> "Relational · SQL editor · port 5432"
        Engine.REDIS -> "Key–value · console · port 6379"
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
                tag = "replace-password",
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
                    .testTag("field-password"),
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
    tag: String,
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
                .testTag(tag),
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
    tag: String,
    glyph: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
    ) {
        // The row owns the click, so the box itself must not also handle it.
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        glyph?.let { Glyph(it, modifier = Modifier.padding(start = Space.md)) }
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
        modifier = Modifier.testTag("field-error"),
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
