package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.Pane
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Environment

/**
 * The unlocked application: connections on the left, the selected one on the right.
 *
 * The shell across the top carries the selected connection's environment, so the
 * production warning is visible no matter how far the user has scrolled — which is
 * the point M2 will depend on, when there is a query editor under it.
 */
@Composable
fun WorkspaceScreen(viewModel: ConnectionsViewModel, onLock: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            WorkspaceBar(selected = viewModel.selected, onLock = onLock)
            HorizontalDivider()

            Row(modifier = Modifier.fillMaxSize()) {
                ConnectionList(
                    connections = viewModel.connections,
                    selectedId = viewModel.selected?.id,
                    enabled = !viewModel.busy,
                    onSelect = viewModel::select,
                    onCreate = viewModel::startCreating,
                    modifier = Modifier.width(280.dp),
                )
                VerticalDivider(modifier = Modifier.fillMaxHeight())

                Column(modifier = Modifier.fillMaxSize()) {
                    viewModel.failure?.let { failure ->
                        ErrorBanner(
                            failure = failure,
                            onDismiss = viewModel::dismissFailure,
                            modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp),
                        )
                    }
                    viewModel.testResult?.let { result ->
                        TestResultBanner(
                            result = result,
                            modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp),
                        )
                    }

                    when (val pane = viewModel.pane) {
                        Pane.Empty -> EmptyPane()
                        is Pane.Form -> ConnectionForm(
                            form = pane.state,
                            busy = viewModel.busy,
                            onSave = viewModel::save,
                            onCancel = viewModel::cancelForm,
                        )

                        is Pane.Detail -> {
                            val view = viewModel.connections.firstOrNull { it.id == pane.id }
                            if (view == null) EmptyPane() else ConnectionDetail(
                                view = view,
                                activity = viewModel.activity,
                                onEdit = { viewModel.startEditing(view) },
                                onTest = { viewModel.test(view.id) },
                                onOpen = { viewModel.open(view.id) },
                                onClose = { viewModel.close(view.id) },
                                onDelete = { viewModel.confirmDelete(view) },
                            )
                        }
                    }
                }
            }
        }
    }

    viewModel.pendingDelete?.let { pending ->
        DeleteConfirmation(
            view = pending,
            onConfirm = { viewModel.delete(pending.id) },
            onCancel = viewModel::cancelDelete,
        )
    }
}

/**
 * The application shell. When the selected connection is production, the whole bar
 * turns red and says so — the warning does not scroll away with the detail pane.
 */
@Composable
private fun WorkspaceBar(selected: ConnectionView?, onLock: () -> Unit) {
    val production = selected?.config?.environment == Environment.PROD
    val background =
        if (production) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
    val foreground =
        if (production) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = if (production) "shell-prod" else "shell-normal" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Database IDE", style = MaterialTheme.typography.titleSmall, color = foreground)
            if (selected != null) {
                Text("·", color = foreground)
                Text(selected.config.name, style = MaterialTheme.typography.bodyMedium, color = foreground)
                EnvironmentBadge(selected.config.environment)
                if (selected.config.readOnly) ReadOnlyBadge()
            }
        }
        TextButton(
            onClick = onLock,
            modifier = Modifier.semantics { contentDescription = "lock-application" },
        ) {
            Text("Lock")
        }
    }
}

@Composable
private fun EmptyPane() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Select a connection, or create one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "workspace-empty" },
        )
    }
}
