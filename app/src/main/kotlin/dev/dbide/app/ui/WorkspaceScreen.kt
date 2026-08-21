package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.EditorTabs
import dev.dbide.app.FocusRequest
import dev.dbide.app.HistoryViewModel
import dev.dbide.app.Pane
import dev.dbide.app.RedisWorkspace
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.Shortcut
import dev.dbide.app.Shortcuts
import dev.dbide.app.ThemeViewModel
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.RuntimeStatus
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
    tabs: EditorTabs,
    redis: RedisWorkspace,
    history: HistoryViewModel,
    theme: ThemeViewModel,
    onLock: () -> Unit,
    shortcuts: Shortcuts = remember { Shortcuts() },
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
        // Every tab already open on this connection, and a first one if it has never
        // had any. A tab pointed at a different server is left exactly as it is.
        tabs.show(postgres?.config)
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
    val panes = tabsFor(browsing?.config?.engine)
    var tab: WorkspaceTab by remember(browsing?.id) { mutableStateOf(panes.first()) }

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

    // §4.5's switcher and §4.4's shortcut reference. Both are windows over the
    // workspace, and both are opened by a chord and by a button, because a control
    // that only has a chord is a control most people never find.
    var switcherOpen: Boolean by remember { mutableStateOf(false) }
    var shortcutsOpen: Boolean by remember { mutableStateOf(false) }

    // Where the keyboard goes after a chord that opens something. Left as a request
    // rather than taken directly, because the pane being asked for may not be on
    // screen yet — choosing a closed connection has to dial it first.
    val editorFocus = remember { FocusRequest() }
    val keyFocus = remember { FocusRequest() }

    // The tab a chord would act on: the one in front, on the connection in front.
    val editor = postgres?.let { tabs.active(it.id) }
    val querying = tab == WorkspaceTab.QUERY

    // Whether something is currently asking the user a question.
    //
    // §4.4: a shortcut must not fire while a dialog needs the keyboard. Every window
    // and confirmation this screen can raise is listed here, and anything added later
    // belongs in the list — a chord that opens a second window over the first, or
    // starts a query behind a confirmation the user is still reading, is worse than a
    // chord that does nothing.
    val modal = viewModel.pane is Pane.Form ||
        settings ||
        historyOpen ||
        switcherOpen ||
        shortcutsOpen ||
        viewModel.pendingDelete != null ||
        tabs.closing != null ||
        editor?.editor?.pending != null ||
        editor?.editor?.pendingScript != null ||
        redis.console.pending != null

    // The chords stop meaning anything when this screen is not on screen. Locking
    // takes the workspace out of the composition and leaves the same window behind it,
    // and a binding that outlived it would let ⌘K open a connection switcher over the
    // lock screen — the one place in the application that must show nothing.
    DisposableEffect(shortcuts) { onDispose { shortcuts.bind { false } } }

    // Re-registered every composition, so the handler answers with what is on screen
    // now rather than what was when the window opened.
    SideEffect {
        shortcuts.bind { shortcut ->
            if (modal) return@bind false
            when (shortcut) {
                Shortcut.HELP -> {
                    shortcutsOpen = true
                    true
                }

                Shortcut.SWITCH -> {
                    switcherOpen = true
                    true
                }

                // The schema tree has no search box to focus, so this is the key
                // browser's or it is nothing. Answering `false` leaves the chord to
                // whatever else wants it rather than swallowing it into a no-op.
                Shortcut.FIND -> if (redisView != null) {
                    keyFocus.raise()
                    true
                } else {
                    false
                }

                Shortcut.NEW_TAB -> if (postgres != null) {
                    tab = WorkspaceTab.QUERY
                    tabs.open(postgres.config)
                    editorFocus.raise()
                    true
                } else {
                    false
                }

                // Everything below acts on the tab in front of the user, so it fires
                // only while that tab is what is on screen. The same chord with the
                // connection's own details showing would be acting on a pane behind
                // the one being looked at.
                Shortcut.CLOSE_TAB -> if (querying && editor != null) {
                    tabs.requestClose(editor)
                    true
                } else {
                    false
                }

                Shortcut.RUN -> if (querying && editor != null) {
                    editor.editor.execute()
                    true
                } else {
                    false
                }

                Shortcut.CANCEL -> if (querying && editor?.running == true) {
                    editor.editor.cancel()
                    true
                } else {
                    false
                }
            }
        }
    }

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

    // The workspace answers chords itself, in the bubble phase, so a key the editor
    // or a search box wanted has already been taken by the time it arrives here. The
    // window in `Main` hands over the events that reach nothing at all — the ones
    // pressed when focus is nowhere.
    Surface(
        modifier = Modifier.fillMaxSize().onKeyEvent(shortcuts::dispatch),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            WorkspaceBar(
                selected = current,
                sidebar = sidebar,
                shortcuts = shortcuts,
                onToggleSidebar = { sidebar = !sidebar },
                onOpenSwitcher = { switcherOpen = true },
                onOpenHistory = {
                    history.open(current?.id)
                    historyOpen = true
                },
                onOpenShortcuts = { shortcutsOpen = true },
                onOpenSettings = { settings = true },
                onLock = onLock,
            )
            Hairline()

            // §4.9's zoom clause. The two side panes are fixed widths, and at 200%
            // on a small laptop they are wider than the window: 264 and 288 into 380
            // leaves the editor — Run, Cancel, and the statement itself — with
            // nothing, and Compose does not complain, it just lays the pane out past
            // the right-hand edge where nobody can see it.
            //
            // So the panes give way in the order the window can afford to lose them.
            // The connection list goes first: it is the one whose job is already done
            // once a connection is chosen, and the switcher chord reaches every one
            // of them without it. The object browser goes second. The editor never
            // goes, because it is what the window is for.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val room = maxWidth
                val browserWidth = if (postgres != null) Sizes.browser else Sizes.keyBrowser
                val roomForBrowser = room - browserWidth >= Sizes.workbenchMin
                val showSidebar = sidebar && room - Sizes.sidebar - browserWidth >= Sizes.workbenchMin

                Row(modifier = Modifier.fillMaxSize()) {
                if (showSidebar) {
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

                if (postgres != null && roomForBrowser) {
                    SchemaTree(
                        model = tree,
                        actions = TreeActions(
                            // Straight into the script at the caret. The text arrives
                            // quoted, so a mixed-case table is the table it names —
                            // and inserting brings the editor forward, because a name
                            // typed into a pane nobody is looking at has gone nowhere.
                            insert = { sql ->
                                tab = WorkspaceTab.QUERY
                                val config = postgres.config
                                val target = tabs.active(config.id) ?: tabs.open(config)
                                target.editor.insert(sql)
                            },
                            copy = copy,
                        ),
                        modifier = Modifier
                            .width(Sizes.browser)
                            .background(MaterialTheme.colorScheme.background),
                    )
                    VerticalHairline()
                }

                if (redisView != null && roomForBrowser) {
                    RedisKeyBrowser(
                        model = redis.browser,
                        focus = keyFocus,
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
                        EmptyPane(any = viewModel.connections.isNotEmpty(), onCreate = viewModel::startCreating)
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
                        // nothing else, which is also the screen that reopens it —
                        // except while it is being dialled, or while it is down with
                        // the user's scripts still in this process. §4.7: those two are
                        // not "here are some settings", they are "wait" and "this
                        // broke, your work is safe, here is the button".
                        if (browsing?.id != view.id) {
                            when {
                                view.runtime.status == RuntimeStatus.OPENING ->
                                    Connecting(view.config.name)

                                tabs.of(view.id).isNotEmpty() -> Disconnected(
                                    view = view,
                                    tabs = tabs.of(view.id).size,
                                    busy = viewModel.busy,
                                    onReconnect = { viewModel.open(view.id) },
                                )

                                else -> detail()
                            }
                        } else {
                            WorkspaceTabs(tabs = panes, selected = tab, onSelect = { tab = it })
                            Hairline()
                            when (tab) {
                                WorkspaceTab.QUERY -> postgres?.let { open ->
                                    QueryWorkspace(
                                        tabs = tabs,
                                        connection = open.config,
                                        // Only servers this process already has a
                                        // client for. Moving a tab must not be the
                                        // thing that dials one.
                                        others = viewModel.connections
                                            .filter {
                                                it.runtime.isOpen &&
                                                    it.config.engine == Engine.POSTGRES &&
                                                    it.id != open.id
                                            }
                                            .map { it.config },
                                        onMoved = viewModel::select,
                                        onCopy = copy,
                                        shortcuts = shortcuts,
                                        focus = editorFocus,
                                    )
                                }
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

    if (shortcutsOpen) ShortcutsDialog(shortcuts = shortcuts, onDismiss = { shortcutsOpen = false })

    if (switcherOpen) {
        ConnectionSwitcher(
            connections = viewModel.connections,
            shortcuts = shortcuts,
            onChoose = { view ->
                switcherOpen = false
                viewModel.select(view.id)
                // Opened if it is not, exactly as double-clicking the row does — and
                // nothing else. §4.5 is explicit that switching is about where new
                // work goes; the tabs already pointed at other servers stay pointed
                // at them, because retargeting one is a decision taken on the tab.
                if (!view.runtime.isOpen) {
                    viewModel.open(view.id)
                } else {
                    tab = tabsFor(view.config.engine).first()
                }
                // And the keyboard follows, into whichever pane the engine lands in.
                // The request waits if the connection is still being dialled.
                when (view.config.engine) {
                    Engine.POSTGRES -> editorFocus.raise()
                    Engine.REDIS -> keyFocus.raise()
                }
            },
            onDismiss = { switcherOpen = false },
        )
    }

    if (historyOpen) {
        HistoryWindow(
            model = history,
            connections = viewModel.connections,
            actions = HistoryActions(
                copy = copy,
                // Only where there is a connection open on that same server. Opening
                // it first would be this window dialling production because someone
                // clicked a row to read it.
                openInEditor = postgres?.let { open ->
                    { record ->
                        historyOpen = false
                        tab = WorkspaceTab.QUERY
                        val target = tabs.active(open.id) ?: tabs.open(open.config)
                        target.editor.open(record.statement)
                    }
                },
                openInNewTab = postgres?.let { open ->
                    { record ->
                        historyOpen = false
                        tab = WorkspaceTab.QUERY
                        tabs.open(open.config, record.statement)
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
            onConfirm = {
                // The tabs go with it. There would be nowhere left to run what is in
                // them, and the question of whether to keep them has just been
                // answered by deleting the server they belong to.
                tabs.forget(pending.id)
                viewModel.delete(pending.id)
            },
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
    shortcuts: Shortcuts,
    onToggleSidebar: () -> Unit,
    onOpenSwitcher: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenShortcuts: () -> Unit,
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
            .testTag(if (production) "shell-prod" else "shell-normal"),
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
                tag = "toggle-sidebar",
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
            // §4.4 asks that every chord also be reachable by pointer, and the two
            // that open a window of their own are the two that would otherwise have
            // nowhere to be clicked. Both carry their chord in a tooltip rather than
            // in the label: the bar is read once a session and the labels have to
            // stay short enough to leave room for the connection's name.
            ToolButton(
                text = "Go to…",
                onClick = onOpenSwitcher,
                tag = "open-switcher",
                tooltip = "${Shortcut.SWITCH.action}  ${shortcuts.chord(Shortcut.SWITCH)}",
            )
            ToolButton(
                text = "History",
                onClick = onOpenHistory,
                tag = "open-history",
            )
            ToolButton(
                text = "Shortcuts",
                onClick = onOpenShortcuts,
                tag = "open-shortcuts",
                tooltip = "${Shortcut.HELP.action}  ${shortcuts.chord(Shortcut.HELP)}",
            )
            ToolButton(
                text = "Settings",
                onClick = onOpenSettings,
                tag = "open-settings",
            )
            ToolButton(text = "Lock", onClick = onLock, tag = "lock-application")
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
                    .selectable(selected = active, role = Role.Tab) { onSelect(entry) }
                    .handCursor()
                    .padding(horizontal = Space.xl)
                    .testTag("workspace-tab-${entry.name.lowercase()}")
                    .arrowsWalkTabs(),
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
 * The connection is being dialled.
 *
 * A named wait rather than a blank pane. Opening a server can take a few seconds
 * behind a VPN, and a pane that shows nothing for those seconds is indistinguishable
 * from a pane that has broken.
 */
@Composable
private fun Connecting(name: String) {
    Box(
        modifier = Modifier.fillMaxSize().testTag("workspace-connecting"),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = "Connecting to $name…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.lg),
            )
        }
    }
}

/**
 * The connection is down and this window still holds work that was open on it.
 *
 * §4.7 asks for Reconnect "without discarding editor text", and the text was never
 * in danger — `EditorTabs` keeps a connection's tabs when the connection goes away,
 * and hands them back when it returns. What was missing is anyone saying so. A user
 * whose editor has just been replaced by a page of connection settings has every
 * reason to assume the twenty lines they had not run are gone, and the only way to
 * find out otherwise is to risk finding out they were right.
 *
 * So the count is said out loud, the reason the server gave is repeated here rather
 * than left on the connection tab, and Reconnect is the primary action on the pane
 * the work was on.
 */
@Composable
private fun Disconnected(
    view: ConnectionView,
    tabs: Int,
    busy: Boolean,
    onReconnect: () -> Unit,
) {
    EmptyState(
        title = if (view.runtime.status == RuntimeStatus.ERROR) {
            "${view.config.name} is not connected"
        } else {
            "${view.config.name} is closed"
        },
        detail = listOfNotNull(
            view.runtime.lastError,
            if (tabs == 1) {
                "Your open tab and everything in it is still here, and comes back with " +
                    "the connection."
            } else {
                "Your $tabs open tabs and everything in them are still here, and come " +
                    "back with the connection."
            },
        ).joinToString(" "),
        tag = "workspace-disconnected",
        action = {
            ToolButton(
                // Never left disabled without a word for why: while something else is
                // in flight the button says what it is waiting on.
                text = if (busy) "Working…" else "Reconnect",
                onClick = onReconnect,
                tag = "workspace-reconnect",
                enabled = !busy,
                emphasis = ToolEmphasis.PRIMARY,
            )
        },
    )
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
private fun EmptyPane(any: Boolean, onCreate: () -> Unit) {
    EmptyState(
        title = if (any) "No connection selected" else "No connections yet",
        detail = if (any) {
            "Select a connection, or create one."
        } else {
            "Add a PostgreSQL or Redis server and it opens here. Everything stays on this " +
                "machine, encrypted under your master password."
        },
        tag = "workspace-empty",
        action = {
            // On both, not only on the first run. A populated install with nothing
            // selected still has a sidebar to click, but the button costs one line and
            // spares the user hunting for the one in a sidebar they may have collapsed.
            ToolButton(
                text = "Add connection",
                onClick = onCreate,
                tag = "workspace-empty-create",
                emphasis = ToolEmphasis.PRIMARY,
            )
        },
    )
}
