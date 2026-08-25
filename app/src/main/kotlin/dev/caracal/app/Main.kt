package dev.caracal.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.caracal.app.ui.AppSurface
import dev.caracal.app.ui.CaracalTheme
import dev.caracal.app.ui.QuitConfirmation
import dev.caracal.app.ui.ThemeMode
import dev.caracal.app.ui.VaultScreen
import dev.caracal.app.ui.VaultUnavailableScreen
import dev.caracal.app.ui.WorkspaceScreen
import dev.caracal.core.appdata.AppPaths
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.connections.DefaultConnectionService
import dev.caracal.core.export.CsvExport
import dev.caracal.core.registry.ConnectionRegistry
import dev.caracal.core.store.ConfigStore
import dev.caracal.core.vault.MetadataStore
import dev.caracal.core.vault.Vault
import java.awt.Dimension
import java.awt.FileDialog
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Everything the application owns for its lifetime, opened once and closed once.
 *
 * The configuration database is opened before the window has anything to show, so a
 * database that cannot be opened becomes a visible message rather than a stack
 * trace on a terminal nobody is watching.
 */
private class Application(
    private val store: ConfigStore,
    val service: ConnectionService,
    /** The theme preference, read while the store was being opened. */
    val theme: ThemeMode,
) : AutoCloseable {
    /**
     * The unencrypted corner of the configuration database.
     *
     * Only settings that are not secrets go here, and only ones the application has
     * to know before anyone has unlocked anything — which today means the theme.
     */
    val preferences: MetadataStore get() = store

    override fun close() {
        // Clients first: a pool closed after its configuration database is a pool
        // that can no longer report what it was.
        //
        // On an IO thread rather than this one, and bounded. Compose disposes the
        // composition on the AWT event thread, so a bare `runBlocking` here blocked
        // the EDT — and `shutdown` waits on the registry's per-connection locks,
        // whose holders resume on `Dispatchers.Main`, which is that same blocked
        // thread. Closing the window while a connection was still dialing deadlocked
        // the two against each other and the process never exited. Running the block
        // elsewhere leaves the EDT free to deliver those continuations, and the
        // timeout means a server that will not answer costs a few seconds on the way
        // out rather than a process that has to be killed.
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(SHUTDOWN_TIMEOUT) { service.shutdown() }
                ?: log.debug("shutdown timed out; closing the store anyway")
        }
        store.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(Application::class.java)

        /**
         * How long the window waits for clients to close on the way out. Long enough
         * for a healthy pool, short enough that an unreachable server does not hold
         * the process open.
         */
        private val SHUTDOWN_TIMEOUT = 5.seconds

        suspend fun open(): Application = withContext(Dispatchers.IO) {
            // Before anything opens the configuration database: an installation from
            // before the application was named Caracal is moved to the new name, so
            // that a rename does not read as a vault that lost every connection in it.
            AppPaths.adoptLegacyData()
            val store = ConfigStore.open(AppPaths.configDatabase())
            val vault = Vault(store, store)
            val registry = ConnectionRegistry()
            Application(
                store = store,
                service = DefaultConnectionService(store, vault, registry),
                // Read here rather than from the composition, so the theme arrives
                // with the store instead of one frame after it.
                theme = ThemeViewModel.read(store),
            )
        }
    }
}

/**
 * `--version` answers and exits without opening anything — no window, no
 * configuration database, no vault. It is how a bug report, a package manifest, or a
 * release smoke test establishes which build is installed, and all three of those
 * need an answer from a machine with no display attached.
 *
 * On macOS the executable is inside the bundle:
 * `Caracal.app/Contents/MacOS/Caracal --version`.
 */
fun main(args: Array<String>) {
    if (args.any { it == "--version" || it == "-version" }) {
        println(BuildInfo.current.describe())
        return
    }
    window()
}

/**
 * The one scope every view model in the window is launched from: connections, the
 * schema tree, every editor and export per tab, history, the theme, the vault.
 *
 * A supervisor, and a handler. The scope Compose hands out by default is a plain Job
 * parented to the recomposer, and a single child completing exceptionally would
 * cancel it and take the other eleven view models with it.
 *
 * The supervisor cannot be supplied as context to `rememberCoroutineScope`, which is
 * the obvious way to write that and the way this shipped. That function rejects any
 * context carrying a `Job` — and it rejects it not by throwing, but by handing back a
 * scope whose job has *already completed exceptionally*. Every `launch` on it is then
 * a silent no-op. Nothing is logged, because nothing ever runs to fail: the vault
 * never reads its own state, so the screen stays on `VaultUiState.Loading`, which is
 * one of the two states that draw nothing, and the window is a bare rectangle of the
 * theme's background colour with no error anywhere to say why.
 *
 * So the supervisor is grafted on afterwards instead, parented to the composition's
 * own job so that leaving the composition still cancels everything under it.
 */
@Composable
internal fun rememberSupervisorScope(): CoroutineScope {
    val composition = rememberCoroutineScope()
    return remember(composition) {
        val context = composition.coroutineContext
        CoroutineScope(
            context + SupervisorJob(context[Job]) + CoroutineExceptionHandler { _, problem ->
                LoggerFactory.getLogger("dev.caracal.app").debug("a view model coroutine failed", problem)
            },
        )
    }
}

private fun window() = application {
    val scope = rememberSupervisorScope()
    val startup = rememberStartup { Application.open() }

    // What the window asks before it closes. It is created out here because
    // `onCloseRequest` is not a composable and arrives from AWT, and it answers
    // `false` only while there is a script open that closing would lose.
    val exit = remember { ExitGuard() }

    // §4.4's chords. The window is where a key press with nothing focused arrives, so
    // it is where the last-resort handler goes; the workspace registers what the
    // chords mean and answers the ones that reach it first.
    val shortcuts = remember { Shortcuts() }

    Window(
        onCloseRequest = { if (exit.mayClose()) exitApplication() },
        // Bubble rather than preview, so a key the editor or a search box wanted has
        // already been taken by the time it gets here. Nothing fires while the quit
        // question is on screen: that dialog owns the keyboard until it is answered,
        // and it is the one modal the workspace underneath cannot see.
        onKeyEvent = { !exit.pending && shortcuts.dispatch(it) },
        title = "Caracal",
        // The packaged application takes its icon from the bundle, but a window
        // running from source has none, and on Linux and Windows this is also the
        // taskbar's. Rendered from `branding/caracal-source.png`; see `app/icons`.
        icon = painterResource("caracal.png"),
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
    ) {
        window.minimumSize = Dimension(760, 480)
        ReportColdStart()

        // The theme arrives with the store, already read. Before that — the frames
        // before the store is open, and the one that says it could not be — it is the
        // default, which is why the default is the one most people would have chosen.
        val theme = remember(startup) {
            val ready = (startup as? Startup.Ready)?.value
            ThemeViewModel(ready?.preferences, scope, ready?.theme ?: ThemeMode.DEFAULT)
        }

        CaracalTheme(theme.mode) {
            // What AWT clears the frame to before Skia has painted anything into it:
            // on realization, and on every live resize, where the toolkit outruns the
            // renderer and paints the gap itself. The platform default is white.
            val shell = MaterialTheme.colorScheme.background
            LaunchedEffect(shell) { window.background = java.awt.Color(shell.toArgb()) }

            // Painted, and painted first: two of the states below draw nothing at all,
            // and both of them are at launch. See [AppSurface].
            AppSurface {
                when (val state = startup) {
                    // The first frame, before the configuration database has been opened.
                    Startup.Opening -> Unit
                    is Startup.Failed -> VaultUnavailableScreen(VaultUiState.Unavailable(state.failure))
                    is Startup.Ready -> Workspace(
                        service = state.value.service,
                        theme = theme,
                        scope = scope,
                        exit = exit,
                        shortcuts = shortcuts,
                        onQuit = ::exitApplication,
                    )
                }
            }
        }
    }
}

/**
 * The locked/unlocked split. Both view models live for the window's lifetime, so
 * locking and unlocking again does not lose the list or leak a scope.
 */
@Composable
private fun FrameWindowScope.Workspace(
    service: ConnectionService,
    theme: ThemeViewModel,
    scope: CoroutineScope,
    exit: ExitGuard,
    shortcuts: Shortcuts,
    onQuit: () -> Unit,
) {
    val connections = remember(service) { ConnectionsViewModel(service, scope) }
    val tree = remember(service) { SchemaTreeViewModel(service, scope) }
    val chooser = rememberCsvFileChooser()
    val databaseChooser = rememberDatabaseFileChooser()
    val tabs = remember(service, chooser) { EditorTabs(service, scope, chooser) }
    val redis = remember(service) { RedisWorkspace(service, scope) }
    val history = remember(service) { HistoryViewModel(service, scope) }
    val vault = remember(service) {
        VaultViewModel(service, scope, onUnlocked = { connections.refresh() })
    }

    LaunchedEffect(vault) { vault.load() }

    // §4.3's shutdown warning. A closed window cannot ask, so the question is
    // registered while there is still something to ask about.
    LaunchedEffect(exit, tabs) { exit.guard { tabs.dirty } }

    when (val screen = vault.screen) {
        is VaultUiState.Unavailable -> VaultUnavailableScreen(screen)
        VaultUiState.Loading -> Unit
        VaultUiState.Setup, VaultUiState.Locked -> VaultScreen(vault, theme)
        VaultUiState.Unlocked -> WorkspaceScreen(
            viewModel = connections,
            tree = tree,
            tabs = tabs,
            redis = redis,
            history = history,
            theme = theme,
            shortcuts = shortcuts,
            choosePath = databaseChooser,
            onLock = {
                connections.clear()
                // Locking closes every client, so the tree is describing a server this
                // process can no longer reach and every tab has nowhere to send a
                // statement — including one that is running right now. Their exports go
                // with them: a running export holds a live pool connection, and locking
                // has just closed every one of them, so stopping it here is what turns
                // that into a deleted partial file rather than a failure to read.
                tree.clear()
                tabs.clear()
                // The keyspace, the open value, and the console transcript. The last of
                // those is the one that matters most: a command's arguments can be a
                // password, and they must not survive the session that typed them.
                redis.clear()
                // History is read from the same database the sealed credentials are
                // in, and it is a record of what was run against which server. A
                // locked application keeping a page of it on screen would be a locked
                // application still showing the thing locking is for.
                history.clear()
                vault.lock()
            },
        )
    }

    // Over everything, including the lock screen: a window can be closed from a state
    // its workspace is not on screen in.
    if (exit.pending) QuitConfirmation(onConfirm = onQuit, onCancel = exit::dismiss)
}

/**
 * The save dialog an export writes into: the platform's own, parented to the window.
 *
 * AWT's `FileDialog` rather than Swing's `JFileChooser` because it is the real
 * thing — Finder on macOS, Explorer on Windows — which is also what makes the
 * overwrite warning, the sidebar, and the network volumes the user's rather than
 * this application's reimplementation of them.
 *
 * The suggested name arrives already sanitized by [CsvExport.fileName]. The name
 * that comes *back* is the user's and is taken as typed, with one exception: a name
 * naming no extension at all gets `.csv`, because a file called `invoices` opens in
 * nothing. Renaming their `notes.txt` to `notes.csv` would be correcting a choice
 * rather than completing one.
 *
 * It suspends on the main dispatcher, which under Compose Desktop is the AWT event
 * thread — where a modal dialog must be shown, and where it pumps its own events
 * while it blocks.
 */
@Composable
private fun FrameWindowScope.rememberCsvFileChooser(): FileChooser {
    val owner = window
    return remember(owner) {
        { suggestion ->
            withContext(Dispatchers.Main) {
                val dialog = FileDialog(owner, "Export CSV", FileDialog.SAVE)
                dialog.file = suggestion
                dialog.isVisible = true
                val directory = dialog.directory
                val chosen = dialog.file
                if (directory == null || chosen == null) null else Path.of(directory, chosen.withCsv())
            }
        }
    }
}

/** [this] as typed, unless it names no extension at all. */
private fun String.withCsv(): String = if (contains('.')) this else "$this.csv"

/**
 * The open dialog a declared file field browses with: the platform's own, parented to
 * the window.
 *
 * The same `FileDialog` the export uses, in `LOAD` rather than `SAVE`, and for the same
 * reason — it is Finder on macOS and Explorer on Windows, which is what makes the
 * sidebar, the network volumes and the recent places the user's own rather than this
 * application's reimplementation of them.
 *
 * The engine's declared extensions become a filter, and the filter is deliberately a
 * *hint*: `setFilenameFilter` is honoured on some platforms and ignored on others, and
 * a file the user names anyway is opened anyway. That is the right way round for
 * SQLite, where the extension means nothing — a database is a database because of the
 * header inside it, and plenty of them are called `.data` or have no extension at all.
 *
 * It suspends on the main dispatcher, which under Compose Desktop is the AWT event
 * thread — where a modal dialog must be shown, and where it pumps its own events while
 * it blocks.
 */
@Composable
private fun FrameWindowScope.rememberDatabaseFileChooser(): FilePicker {
    val owner = window
    return remember(owner) {
        { field ->
            withContext(Dispatchers.Main) {
                val dialog = FileDialog(owner, "Open ${field.label.lowercase()}", FileDialog.LOAD)
                if (field.extensions.isNotEmpty()) {
                    dialog.setFilenameFilter { _, name ->
                        field.extensions.any { name.endsWith(it, ignoreCase = true) }
                    }
                }
                dialog.isVisible = true
                val directory = dialog.directory
                val chosen = dialog.file
                if (directory == null || chosen == null) null else Path.of(directory, chosen)
            }
        }
    }
}

/**
 * Cold start is a number this project has to keep an eye on — the JVM is the price of
 * the stack move. Set CARACAL_LOG_STARTUP=1 to have the first rendered frame report it.
 */
@Composable
private fun ReportColdStart() {
    if (System.getenv("CARACAL_LOG_STARTUP") != "1") return
    LaunchedEffect(Unit) {
        withFrameNanos { }
        // Measured from JVM start, so it excludes the launcher's own process spawn.
        println("startup: first frame after ${ManagementFactory.getRuntimeMXBean().uptime} ms")
    }
}
