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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.EditorViewModel
import dev.dbide.app.ExportViewModel
import dev.dbide.app.Pane
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.launch

/** Which half of an open connection the right-hand pane is showing. */
private enum class WorkspaceTab(val label: String) { QUERY("Query"), CONNECTION("Connection") }

/**
 * The unlocked application: connections on the left, the object browser beside them
 * once one is open, and the selected connection on the right.
 *
 * The shell across the top carries the selected connection's environment, so the
 * production warning is visible no matter how far the user has scrolled — which is
 * the point of it now that there is a query editor underneath.
 */
@Composable
fun WorkspaceScreen(
    viewModel: ConnectionsViewModel,
    tree: SchemaTreeViewModel,
    editor: EditorViewModel,
    export: ExportViewModel,
    onLock: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Only an open PostgreSQL connection has a catalog to read. A closed one is not
    // reopened to fill a panel: the user closed it.
    val browsing = viewModel.selected
        ?.takeIf { it.config.engine == Engine.POSTGRES && it.runtime.isOpen }
    // Keyed on the configuration and not just the identifier: §2.4's policy asks the
    // connection whether it is read only and which environment it is, so an edit to
    // either has to reach the editor without the connection being reopened.
    LaunchedEffect(browsing?.config) {
        tree.show(browsing?.id)
        editor.show(browsing?.config)
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(clipEntryOf(text)) } }

    // Opening a connection lands on its editor; the connection's own details are one
    // click away and stay there per connection, so switching back and forth does not
    // keep resetting which half is on screen.
    var tab: WorkspaceTab by remember(browsing?.id) { mutableStateOf(WorkspaceTab.QUERY) }

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

                if (browsing != null) {
                    SchemaTree(
                        model = tree,
                        // Straight into the script at the caret. The name arrives
                        // quoted, so a mixed-case table is the table it names.
                        onInsertIdentifier = { identifier ->
                            tab = WorkspaceTab.QUERY
                            editor.insert(identifier)
                        },
                        modifier = Modifier.width(300.dp),
                    )
                    VerticalDivider(modifier = Modifier.fillMaxHeight())
                }

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
                            val detail: @Composable () -> Unit = {
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
                            // The editor exists only where there is a server to send a
                            // statement to. A closed connection has its details and
                            // nothing else, which is also the screen that reopens it.
                            if (browsing?.id != pane.id) {
                                detail()
                            } else {
                                WorkspaceTabs(selected = tab, onSelect = { tab = it })
                                HorizontalDivider()
                                when (tab) {
                                    WorkspaceTab.QUERY -> QueryPane(editor, export, onCopy = copy)
                                    WorkspaceTab.CONNECTION -> detail()
                                }
                            }
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
 * [text] as something the system clipboard will take.
 *
 * `ClipEntry` wraps an AWT `Transferable` here and is marked experimental. The
 * alternative, `LocalClipboardManager`, is deprecated in favour of precisely this
 * — so between an API that may still change shape and one that is already on its
 * way out, this is the one that will still be here.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun clipEntryOf(text: String) = ClipEntry(StringSelection(text))

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

/** The two halves of an open connection: the editor, and the connection itself. */
@Composable
private fun WorkspaceTabs(selected: WorkspaceTab, onSelect: (WorkspaceTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkspaceTab.entries.forEach { entry ->
            FilterChip(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                label = { Text(entry.label, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.semantics {
                    contentDescription = "workspace-tab-${entry.name.lowercase()}"
                },
            )
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
