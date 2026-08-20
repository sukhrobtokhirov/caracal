package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.EditorViewModel
import dev.dbide.app.ExportViewModel
import dev.dbide.app.Pane
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.ThemeViewModel
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
 *
 * The two left-hand panes sit on the darker of the two surfaces and the working pane
 * on the lighter one. It is a one-value difference and it does the job a border would
 * otherwise have to: the eye reads three panes without three rules being drawn.
 */
@Composable
fun WorkspaceScreen(
    viewModel: ConnectionsViewModel,
    tree: SchemaTreeViewModel,
    editor: EditorViewModel,
    export: ExportViewModel,
    theme: ThemeViewModel,
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            WorkspaceBar(selected = viewModel.selected, theme = theme, onLock = onLock)
            Hairline()

            Row(modifier = Modifier.fillMaxSize()) {
                ConnectionList(
                    connections = viewModel.connections,
                    selectedId = viewModel.selected?.id,
                    enabled = !viewModel.busy,
                    onSelect = viewModel::select,
                    onCreate = viewModel::startCreating,
                    modifier = Modifier
                        .width(Sizes.sidebar)
                        .background(MaterialTheme.colorScheme.background),
                )
                VerticalHairline()

                if (browsing != null) {
                    SchemaTree(
                        model = tree,
                        // Straight into the script at the caret. The name arrives
                        // quoted, so a mixed-case table is the table it names.
                        onInsertIdentifier = { identifier ->
                            tab = WorkspaceTab.QUERY
                            editor.insert(identifier)
                        },
                        modifier = Modifier
                            .width(Sizes.browser)
                            .background(MaterialTheme.colorScheme.background),
                    )
                    VerticalHairline()
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    viewModel.failure?.let { failure ->
                        ErrorBanner(
                            failure = failure,
                            onDismiss = viewModel::dismissFailure,
                            modifier = Modifier.padding(Space.lg, Space.lg, Space.lg, 0.dp),
                        )
                    }
                    viewModel.testResult?.let { result ->
                        TestResultBanner(
                            result = result,
                            modifier = Modifier.padding(Space.lg, Space.lg, Space.lg, 0.dp),
                        )
                    }

                    when (val pane = viewModel.pane) {
                        Pane.Empty -> EmptyPane(any = viewModel.connections.isNotEmpty())
                        is Pane.Form -> ConnectionForm(
                            form = pane.state,
                            busy = viewModel.busy,
                            onSave = viewModel::save,
                            onCancel = viewModel::cancelForm,
                        )

                        is Pane.Detail -> {
                            val view = viewModel.connections.firstOrNull { it.id == pane.id }
                            val detail: @Composable () -> Unit = {
                                if (view == null) {
                                    EmptyPane(any = viewModel.connections.isNotEmpty())
                                } else {
                                    ConnectionDetail(
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
                            // The editor exists only where there is a server to send a
                            // statement to. A closed connection has its details and
                            // nothing else, which is also the screen that reopens it.
                            if (browsing?.id != pane.id) {
                                detail()
                            } else {
                                WorkspaceTabs(selected = tab, onSelect = { tab = it })
                                Hairline()
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
 *
 * Otherwise it is the darkest thing on screen. A title bar that competes with the
 * data underneath it is a title bar being read once a day and looked past for the
 * rest, so it is drawn to be looked past.
 */
@Composable
private fun WorkspaceBar(selected: ConnectionView?, theme: ThemeViewModel, onLock: () -> Unit) {
    val production = selected?.config?.environment == Environment.PROD
    val background = if (production) MaterialTheme.colorScheme.errorContainer else Dbide.colors.chrome
    val foreground =
        if (production) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.barHeight)
            .background(background)
            .padding(horizontal = Space.lg)
            .semantics { contentDescription = if (production) "shell-prod" else "shell-normal" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                "Database IDE",
                style = MaterialTheme.typography.titleSmall,
                color = foreground.copy(alpha = if (production) 1f else 0.75f),
            )
            if (selected != null) {
                Text("/", color = foreground.copy(alpha = 0.4f))
                ColorSwatch(selected.config.color)
                Text(
                    selected.config.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                EnvironmentBadge(selected.config.environment, inverted = production)
                if (selected.config.readOnly) ReadOnlyBadge()
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeToggle(mode = theme.mode, onCycle = theme::cycle)
            ToolButton(text = "Lock", onClick = onLock, description = "lock-application")
        }
    }
}

/**
 * The two halves of an open connection: the editor, and the connection itself.
 *
 * Underlined rather than chipped. Chips read as filters — several can be on at once
 * — and these are two views of one thing, exactly one of which is showing.
 */
@Composable
private fun WorkspaceTabs(selected: WorkspaceTab, onSelect: (WorkspaceTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.paneHeader)
            .background(Dbide.colors.paneHeader),
    ) {
        val accent = MaterialTheme.colorScheme.primary
        WorkspaceTab.entries.forEach { entry ->
            val active = entry == selected
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .hoverHighlight()
                    // Drawn rather than laid out. An indicator that is a child of the
                    // tab has to fill its width, and a child filling its width is what
                    // sizes the tab — which stretches the first tab across the pane and
                    // pushes the second one off the edge of it.
                    .drawBehind {
                        if (!active) return@drawBehind
                        val thickness = 2.dp.toPx()
                        drawRect(
                            color = accent,
                            topLeft = Offset(0f, size.height - thickness),
                            size = Size(size.width, thickness),
                        )
                    }
                    .clickable { onSelect(entry) }
                    .handCursor()
                    .padding(horizontal = Space.xl)
                    .semantics { contentDescription = "workspace-tab-${entry.name.lowercase()}" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * The working pane with nothing selected.
 *
 * Two different sentences, because they are two different situations: an empty
 * install has nothing to select and a populated one is waiting for a click. Telling
 * a first-run user to select a connection they do not have is how an application
 * teaches someone that its messages are not worth reading.
 */
@Composable
private fun EmptyPane(any: Boolean) {
    EmptyState(
        title = if (any) "No connection selected" else "No connections yet",
        detail = if (any) {
            "Select a connection, or create one."
        } else {
            "Choose New to add a PostgreSQL or Redis server. Everything stays on this machine."
        },
        description = "workspace-empty",
    )
}
