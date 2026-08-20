package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.dbide.app.PendingWrite
import dev.dbide.core.connections.Environment
import dev.dbide.core.policy.Acknowledgement
import dev.dbide.core.sql.StatementKind

/**
 * The last thing between a classified write and a server.
 *
 * §2.4 asks for two strengths of this and the difference is deliberate. On
 * development and staging a button is enough — the statement is worth naming, not
 * worth obstructing. On production the connection's own name has to be typed,
 * because the failure this dialog exists to prevent is not misunderstanding, it is
 * reflex: a person who has dismissed forty confirmations today will dismiss the
 * forty-first without reading it, and typing `payments-prod` is the one action that
 * cannot be performed by muscle memory aimed at the right-hand button.
 *
 * The production banner stays inside the dialog rather than only in the shell
 * behind it, which §2.4 also asks for and which matters more than it sounds: a
 * modal dims what is behind it, so the red bar that was the standing warning is the
 * one thing on screen that has just stopped being legible.
 */
@Composable
fun WriteConfirmation(
    pending: PendingWrite,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val clearance = pending.clearance
    val production = clearance.environment == Environment.PROD
    var typed by remember(pending) { mutableStateOf("") }
    val satisfied = clearance.satisfiedBy(typed)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title(clearance.kind)) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (production) ProductionBanner(clearance.connectionName)

                Text(
                    text = explanation(clearance.kind, clearance.connectionName, production),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = "write-explanation" },
                )

                // The statement itself, because a confirmation that does not show what
                // it is confirming is asking the user to trust their own memory of
                // which statement the caret was in.
                StatementPreview(pending.target.sql)

                if (clearance.acknowledgement == Acknowledgement.TYPED) {
                    TypedAcknowledgement(
                        connectionName = clearance.connectionName,
                        typed = typed,
                        onTyped = { typed = it },
                        onSubmit = { if (satisfied) onConfirm(typed) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(typed) },
                enabled = satisfied,
                modifier = Modifier.semantics { contentDescription = "confirm-write" },
            ) {
                Text(
                    text = "Run it",
                    color = if (satisfied) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "cancel-write" },
            ) {
                Text("Cancel")
            }
        },
        modifier = Modifier.semantics { contentDescription = "write-confirmation" },
    )
}

/** The standing production warning, repeated where the modal cannot dim it. */
@Composable
private fun ProductionBanner(connectionName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = "write-prod-banner" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EnvironmentBadge(Environment.PROD)
        Text(
            text = connectionName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** What is about to run, as it was written. */
@Composable
private fun StatementPreview(sql: String) {
    Text(
        text = preview(sql),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp)
            .semantics { contentDescription = "write-statement" },
    )
}

/** The production gate: the connection's name, typed out. */
@Composable
private fun TypedAcknowledgement(
    connectionName: String,
    typed: String,
    onTyped: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Focused on open so the keyboard is already where the one required action is.
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Type $connectionName to confirm.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = onTyped,
            singleLine = true,
            placeholder = { Text(connectionName, fontFamily = FontFamily.Monospace) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .semantics { contentDescription = "write-acknowledgement" },
        )
    }
}

private fun title(kind: StatementKind): String = when (kind) {
    StatementKind.UNKNOWN -> "Run an unrecognized statement?"
    else -> "Run a statement that modifies data?"
}

/**
 * Why this dialog is on screen, in the terms that apply.
 *
 * The unrecognized case gets its own sentence rather than being called a write.
 * Telling a user that `REFRESH MATERIALIZED VIEW` modifies data when what actually
 * happened is that the classifier did not recognize it is a small lie, and small
 * lies are how a warning stops being read.
 */
private fun explanation(kind: StatementKind, connectionName: String, production: Boolean): String {
    val what = when (kind) {
        StatementKind.UNKNOWN ->
            "This statement was not recognized as one that only reads, so it may modify data on"

        else -> "This statement modifies data or schema on"
    }
    val where = if (production) "$connectionName, which is production." else "$connectionName."
    return "$what $where"
}

/**
 * [sql] as much of itself as fits.
 *
 * Bounded because a confirmation dialog that scrolls has buttons below the fold,
 * and a user who cannot see the buttons will press Enter — which is the reflex this
 * whole dialog exists to interrupt.
 */
private fun preview(sql: String, lines: Int = 6, width: Int = 400): String {
    val trimmed = sql.trim()
    val head = trimmed.lineSequence().take(lines).joinToString("\n")
    val clipped = if (head.length <= width) head else head.take(width - 1) + "…"
    return if (clipped.length < trimmed.length) "$clipped\n…" else clipped
}
