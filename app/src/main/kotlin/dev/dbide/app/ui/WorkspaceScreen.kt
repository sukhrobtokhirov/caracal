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
import dev.dbide.app.HistoryViewModel
import dev.dbide.app.Pane
import dev.dbide.app.RedisWorkspace
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.ThemeViewModel
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.launch

/**
 * Which view of an open connection the right-hand pane is showing.
 *
 * One enumeration across both engines rather than one each, because the tab strip and
 * the pane behind it are the same control either way. Which entries appear is
 * [tabsFor]'s decision, and it is made from the engine: a PostgreSQL connection has no
 * keyspace and a Redis one has no SQL editor, so offering either would be offering a
 * tab that can only say "not for this engine".
 */
private enum class WorkspaceTab(val label: String, val glyph: String) {
    QUERY("Query", Glyphs.QUERY),
    KEY("Value", Glyphs.VALUE),
    CONSOLE("Console", Glyphs.CONSOLE),
    SERVER("Server", Glyphs.SERVER),
    CONNECTION("Connection", Glyphs.CONNECTIONS),
}

/** The tabs an engine has, in the order they are shown. */
private fun tabsFor(engine: Engine?): List<WorkspaceTab> = when (engine) {
    Engine.POSTGRES -> listOf(WorkspaceTab.QUERY, WorkspaceTab.CONNECTION)
    Engine.REDIS -> listOf(
        WorkspaceTab.KEY,
        WorkspaceTab.CONSOLE,
        WorkspaceTab.SERVER,
        WorkspaceTab.CONNECTION,
    )

    null -> listOf(WorkspaceTab.CONNECTION)
}

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
    redis: RedisWorkspace,
    history: HistoryViewModel,
    theme: ThemeViewModel,
    onLock: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    // What the window is about, which is not always what is selected.
    //
    // Creating a connection selects nothing — there is nothing to select yet — and
    // that used to empty the workspace out behind the form. Now that the form is a
    // window over the workspace, an emptied workspace is visible the whole time it is
    // open: the grid the user was reading disappears, the object browser collapses
    // everything they had expanded, and all of it comes back when they press Cancel.
    // So while a window is open the workspace holds its place.
    var held: ConnectionId? by remember { mutableStateOf(null) }
    LaunchedEffect(viewModel.selected?.id) { viewModel.selected?.id?.let { held = it } }
    val current = viewModel.selected ?: (viewModel.pane as? Pane.Form)?.let {
        viewModel.connections.firstOrNull { view -> view.id == held }
    }

    // Only an open connection has anything to browse. A closed one is not reopened to
    // fill a panel: the user closed it.
    val browsing = current?.takeIf { it.runtime.isOpen }
    val postgres = browsing?.takeIf { it.config.engine == Engine.POSTGRES }
    val redisView = browsing?.takeIf { it.config.engine == Engine.REDIS }
    // Keyed on the configuration and not just the identifier: §2.4's policy asks the
    // connection whether it is read only and which environment it is, so an edit to
    // either has to reach the editor without the connection being reopened.
    LaunchedEffect(postgres?.config) {
        tree.show(postgres?.id)
        editor.show(postgres?.config)
    }
    // The Redis panes take the identifier alone. Their own policy question — whether a
    // console command needs a typed phrase — is asked inside `:core`, against the
    // configuration the connection was opened with.
    LaunchedEffect(redisView?.id) { redis.show(redisView?.id) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(clipEntryOf(text)) } }

    // Opening a connection lands on its editor; the connection's own details are one
    // click away and stay there per connection, so switching back and forth does not
    // keep resetting which half is on screen.
    val tabs = tabsFor(browsing?.config?.engine)
    var tab: WorkspaceTab by remember(browsing?.id) { mutableStateOf(tabs.first()) }

    // The sidebar is a pane, not a fixture. On a laptop beside a terminal the list of
    // connections is read once an hour and the grid is read all day, and 264dp is
    // three more columns of it.
    var sidebar: Boolean by remember { mutableStateOf(true) }

    // The settings window. It is state of the window rather than of the connection
    // list, so it survives selecting a different connection behind it.
    var settings: Boolean by remember { mutableStateOf(false) }

    // The history window, which is the one surface that spans connections: the tab
    // strip belongs to one open server, and "what did I run this morning" is rarely a
    // question about only one of them. It opens filtered to the selected connection,
    // because that is what the user was looking at a moment ago.
    var historyOpen: Boolean by remember { mutableStateOf(false) }

    // What a row can do to itself. Opening also selects, so double-clicking one row
    // while another is selected does not leave the shell naming the wrong server.
    val actions = ConnectionActions(
        activate = { view ->
            viewModel.select(view.id)
            // Already open is not an error and not a reconnect: the editor is what
            // the user was asking for, and it is already there.
            if (!view.runtime.isOpen) {
                viewModel.open(view.id)
            } else {
                tab = tabsFor(view.config.engine).first()
            }
        },
        close = { view -> viewModel.close(view.id) },
        test = { view -> viewModel.test(view.id) },
        edit = viewModel::startEditing,
        delete = viewModel::confirmDelete,
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            WorkspaceBar(
                selected = current,
                sidebar = sidebar,
                onToggleSidebar = { sidebar = !sidebar },
                onOpenHistory = {
                    history.open(current?.id)
                    historyOpen = true
                },
                onOpenSettings = { settings = true },
                onLock = onLock,
            )
            Hairline()

            Row(modifier = Modifier.fillMaxSize()) {
                if (sidebar) {
                    ConnectionList(
                        connections = viewModel.connections,
                        selectedId = viewModel.selected?.id,
                        enabled = !viewModel.busy,
                        onSelect = viewModel::select,
                        onCreate = viewModel::startCreating,
                        actions = actions,
                        onCollapse = { sidebar = false },
                        modifier = Modifier
                            .width(Sizes.sidebar)
                            .background(MaterialTheme.colorScheme.background),
                    )
                    VerticalHairline()
                }

                if (postgres != null) {
                    SchemaTree(
                        model = tree,
                        actions = TreeActions(
                            // Straight into the script at the caret. The text arrives
                            // quoted, so a mixed-case table is the table it names —
                            // and inserting brings the editor forward, because a name
                            // typed into a pane nobody is looking at has gone nowhere.
                            insert = { sql ->
                                tab = WorkspaceTab.QUERY
                                editor.insert(sql)
                            },
                            copy = copy,
                        ),
                        modifier = Modifier
                            .width(Sizes.browser)
                            .background(MaterialTheme.colorScheme.background),
                    )
                    VerticalHairline()
                }

                if (redisView != null) {
                    RedisKeyBrowser(
                        model = redis.browser,
                        // Clicking a key opens it, which means showing the pane its
                        // value is in: a browser that selected a key and left the
                        // console on screen would look like it had done nothing.
                        onOpenKey = { key ->
                            tab = WorkspaceTab.KEY
                            redis.open(key)
                        },
                        modifier = Modifier
                            .width(Sizes.keyBrowser)
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

                    // The form is a window now, so what the working area shows while
                    // one is open is whatever it was showing before: the connection
                    // being edited, or the empty state a new one is being created
                    // from. A modal over a pane that has gone blank reads as though
                    // the application threw the user's place away to ask a question.
                    val view = current
                    if (view == null) {
                        EmptyPane(any = viewModel.connections.isNotEmpty())
                    } else {
                        val detail: @Composable () -> Unit = {
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
                        // The editor exists only where there is a server to send a
                        // statement to. A closed connection has its details and
                        // nothing else, which is also the screen that reopens it.
                        if (browsing?.id != view.id) {
                            detail()
                        } else {
                            WorkspaceTabs(tabs = tabs, selected = tab, onSelect = { tab = it })
                            Hairline()
                            when (tab) {
                                WorkspaceTab.QUERY -> QueryPane(editor, export, onCopy = copy)
                                WorkspaceTab.KEY -> RedisValueViewer(redis.value, onCopy = copy)
                                WorkspaceTab.CONSOLE -> RedisConsole(redis.console)
                                WorkspaceTab.SERVER -> RedisInfoDashboard(redis.info)
                                WorkspaceTab.CONNECTION -> detail()
                            }
                        }
                    }
                }
            }
        }
    }

    // Everything that changes something is a window over the workspace rather than a
    // pane inside it, and all of them are mounted here — after the layout, so they
    // draw over it, and beside each other, so there is one place that says what the
    // application can be asking the user right now.
    (viewModel.pane as? Pane.Form)?.let { form ->
        ConnectionDialog(
            form = form.state,
            busy = viewModel.busy,
            onSave = viewModel::save,
            onCancel = viewModel::cancelForm,
        )
    }

    if (settings) SettingsDialog(theme = theme, onDismiss = { settings = false })

    if (historyOpen) {
        HistoryWindow(
            model = history,
            connections = viewModel.connections,
            actions = HistoryActions(
                copy = copy,
                // Only where there is an editor pointed at that same server. Opening
                // the connection first would be this window dialling production
                // because someone clicked a row to read it.
                openInEditor = postgres?.let {
                    { record ->
                        historyOpen = false
                        tab = WorkspaceTab.QUERY
                        editor.open(record.statement)
                    }
                },
                editorConnection = postgres?.id,
            ),
            onDismiss = { historyOpen = false },
        )
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
private fun WorkspaceBar(
    selected: ConnectionView?,
    sidebar: Boolean,
    onToggleSidebar: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onLock: () -> Unit,
) {
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
            // Leftmost, where a sidebar toggle is on every other desktop application,
            // and lit while the pane is showing — it is a state the window is in.
            ToolButton(
                text = "Sidebar",
                onClick = onToggleSidebar,
                description = "toggle-sidebar",
                emphasis = if (sidebar) ToolEmphasis.PRIMARY else ToolEmphasis.NORMAL,
            )
            AppMark(size = 16.dp)
            Text(
                "Database IDE",
                style = MaterialTheme.typography.titleSmall,
                color = foreground.copy(alpha = if (production) 1f else 0.75f),
            )
            if (selected != null) {
                Text("/", color = foreground.copy(alpha = 0.4f))
                // The engine's own mark, so the bar says what kind of server this is
                // before it says which one. The badge that used to carry it is still
                // in the detail pane, where there is room for a word.
                EngineLogo(selected.config.engine, size = 14.dp, described = true)
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
            // The theme moved inside the settings window. A button that cycles three
            // values can only be understood by pressing it repeatedly, and the window
            // it now lives in shows all three with what each one is for.
            ToolButton(
                text = "History",
                onClick = onOpenHistory,
                description = "open-history",
            )
            ToolButton(
                text = "Settings",
                onClick = onOpenSettings,
                description = "open-settings",
            )
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
private fun WorkspaceTabs(
    tabs: List<WorkspaceTab>,
    selected: WorkspaceTab,
    onSelect: (WorkspaceTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.paneHeader)
            .background(Dbide.colors.paneHeader),
    ) {
        val accent = MaterialTheme.colorScheme.primary
        tabs.forEach { entry ->
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(entry.glyph)
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
