package dev.dbide.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.dbide.app.Activity
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment

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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorSwatch(config.color)
            Text(config.name, style = MaterialTheme.typography.titleMedium)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EngineBadge(config.engine)
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
                modifier = Modifier.semantics { contentDescription = "runtime-error" },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DetailRow("Host", "${config.host}:${config.port}")
            DetailRow(
                if (config.engine == Engine.REDIS) "Database index" else "Database",
                config.database.ifEmpty { "—" },
            )
            DetailRow("Username", config.username.ifEmpty { "—" })
            DetailRow("TLS", config.tlsMode.wire)
            // The password itself is never shown, at any point, in any state.
            DetailRow("Password", if (view.hasSecret) "Saved" else "Not saved")
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onTest,
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "test-connection" },
            ) {
                Text("Test")
            }
            if (view.runtime.isOpen) {
                OutlinedButton(
                    onClick = onClose,
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "close-connection" },
                ) {
                    Text("Close")
                }
            } else {
                OutlinedButton(
                    onClick = onOpen,
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "open-connection" },
                ) {
                    Text("Open")
                }
            }
            OutlinedButton(
                onClick = onEdit,
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "edit-connection" },
            ) {
                Text("Edit")
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "delete-connection" },
            ) {
                Text("Delete")
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).semantics {
                        contentDescription = "busy-${activity.name.lowercase()}"
                    },
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This removes the connection and its saved password. It cannot be undone.",
                )
                if (view.config.environment == Environment.PROD) {
                    Text(
                        "This is a PROD connection.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "confirm-delete" },
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "cancel-delete" },
            ) {
                Text("Cancel")
            }
        },
        modifier = Modifier.semantics { contentDescription = "delete-confirmation" },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
