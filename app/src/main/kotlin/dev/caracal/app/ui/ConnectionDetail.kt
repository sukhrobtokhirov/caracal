package dev.caracal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.caracal.app.Activity
import dev.caracal.core.connections.ConnectionView
import dev.caracal.core.connections.ConnectionDraft
import dev.caracal.core.connections.fields
import dev.caracal.core.engines.Engines
import dev.caracal.engine.api.FormField
import dev.caracal.core.connections.Environment

/** One saved connection: what it points at, what state it is in, and what can be done to it. */
@Composable
fun ConnectionDetail(
    view: ConnectionView,
    activity: Activity,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = activity != Activity.NONE
    val config = view.config

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The engine's mark at heading size. The badge under it still says the
            // word, so the mark is the shortcut and never the only statement.
            EngineTile(config.engineId, size = 36.dp)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorSwatch(config.color)
                    Text(config.name, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    config.targetSummary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EngineBadge(config.engineId)
            EnvironmentBadge(config.environment)
            if (config.readOnly) ReadOnlyBadge()
            StatusBadge(view.runtime)
        }

        // A failed open leaves its reason here, so it survives a dismissed banner.
        view.runtime.lastError?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("runtime-error"),
            )
        }

        // The facts, boxed off from the actions under them. A row of buttons directly
        // beneath a row of values reads as one list, and one of those lists deletes
        // things.
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.md),
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(Space.lg),
        ) {
            // One row per field the engine declared, under the engine's own labels.
            // The pane used to name Host, Database, Username and TLS itself, which
            // made it right about two engines and wrong about the third: a Redis
            // database is an index and an engine that opens a file has no host at all.
            val engine = Engines.byId(config.engineId)
            val stored = ConnectionDraft.of(config).values
            if (engine == null) {
                // An engine this build does not have still has to show what it points
                // at. Its settings are shown under their stored keys, because there is
                // no declaration left to give them labels.
                DetailRow("Target", config.targetSummary)
                config.settings.toSortedMap().forEach { (key, value) -> DetailRow(key, value) }
            } else {
                engine.fields
                    .filterNot { it is FormField.Secret }
                    .forEach { field -> DetailRow(field.label, stored[field.key].orEmpty().ifEmpty { "—" }) }
            }
            // The password itself is never shown, at any point, in any state.
            DetailRow("Password", if (view.hasSecret) "Saved" else "Not saved")
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                shape = MaterialTheme.shapes.small,
                onClick = onTest,
                enabled = !busy,
                modifier = Modifier.testTag("test-connection"),
            ) {
                Text("Test")
            }
            if (view.runtime.isOpen) {
                OutlinedButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = onClose,
                    enabled = !busy,
                    modifier = Modifier.testTag("close-connection"),
                ) {
                    Text("Close")
                }
            } else {
                OutlinedButton(
                    shape = MaterialTheme.shapes.small,
                    onClick = onOpen,
                    enabled = !busy,
                    modifier = Modifier.testTag("open-connection"),
                ) {
                    Text("Open")
                }
            }
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = onEdit,
                enabled = !busy,
                modifier = Modifier.testTag("edit-connection"),
            ) {
                Text("Edit")
            }
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = onDelete,
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(
                    // Red before the dialog, not only inside it. A destructive action
                    // that looks like its four neighbours is one the pointer reaches
                    // by accident.
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag("delete-connection"),
            ) {
                Text("Delete")
            }
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp).semantics {
                        testTag = "busy-${activity.name.lowercase()}"
                    },
                )
                // §4.7: a spinner beside five buttons that have all gone grey says
                // that something is happening and not which of the five it was. The
                // word is what turns a frozen row of controls into a wait.
                Text(
                    text = activity.describe(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("busy-label"),
                )
            }
        }
    }
}

/**
 * Deleting is deliberate. The dialog names the connection and spells out that the
 * saved credential goes with it, because there is no undo and no second copy.
 */
@Composable
fun DeleteConfirmation(view: ConnectionView, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete \"${view.config.name}\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text(
                    "This removes the connection and its saved password. It cannot be undone.",
                )
                if (view.config.environment == Environment.PROD) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(Glyphs.PROD, size = 13)
                        Text(
                            "This is a PROD connection.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm-delete"),
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("cancel-delete"),
            ) {
                Text("Cancel")
            }
        },
        modifier = Modifier.testTag("delete-confirmation"),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        // Monospace, because every one of these is something someone will compare
        // character by character against a configuration file.
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** What a control is disabled for, in one word the user can read while they wait. */
private fun Activity.describe(): String = when (this) {
    Activity.NONE -> ""
    Activity.LOADING -> "Loading…"
    Activity.SAVING -> "Saving…"
    Activity.TESTING -> "Testing the connection…"
    Activity.OPENING -> "Opening the connection…"
    Activity.CLOSING -> "Closing the connection…"
    Activity.DELETING -> "Deleting…"
}
