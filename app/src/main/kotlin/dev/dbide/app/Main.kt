package dev.dbide.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dbide.app.ui.DbideTheme
import dev.dbide.app.ui.QuitConfirmation
import dev.dbide.app.ui.VaultScreen
import dev.dbide.app.ui.VaultUnavailableScreen
import dev.dbide.app.ui.WorkspaceScreen
import dev.dbide.core.appdata.AppPaths
import dev.dbide.core.connections.ConnectionService
import dev.dbide.core.connections.DefaultConnectionService
import dev.dbide.core.export.CsvExport
import dev.dbide.core.registry.ConnectionRegistry
import dev.dbide.core.store.ConfigStore
import dev.dbide.core.vault.MetadataStore
import dev.dbide.core.vault.Vault
import java.awt.Dimension
import java.awt.FileDialog
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
        runBlocking { service.shutdown() }
        store.close()
    }

    companion object {
        suspend fun open(): Application = withContext(Dispatchers.IO) {
            val store = ConfigStore.open(AppPaths.configDatabase())
            val vault = Vault(store)
            val registry = ConnectionRegistry()
            Application(store, DefaultConnectionService(store, vault, registry))
        }
    }
}

fun main() = application {
    val scope = rememberCoroutineScope()
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
        title = "Database IDE",
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
    ) {
        window.minimumSize = Dimension(760, 480)
        ReportColdStart()

        // The theme is bound to the store as soon as there is one. Before that — the
        // first frame, and the frame that says the store could not be opened — it is
        // the default, which is why the default is the one that does not flash.
        val theme = remember(startup) {
            ThemeViewModel((startup as? Startup.Ready)?.value?.preferences, scope)
        }
        LaunchedEffect(theme) { theme.load() }

        DbideTheme(theme.mode) {
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
 * Cold start is a number this project has to keep an eye on — the JVM is the price of
 * the stack move. Set DBIDE_LOG_STARTUP=1 to have the first rendered frame report it.
 */
@Composable
private fun ReportColdStart() {
    if (System.getenv("DBIDE_LOG_STARTUP") != "1") return
    LaunchedEffect(Unit) {
        withFrameNanos { }
        // Measured from JVM start, so it excludes the launcher's own process spawn.
        println("startup: first frame after ${ManagementFactory.getRuntimeMXBean().uptime} ms")
    }
}
