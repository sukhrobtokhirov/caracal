package dev.dbide.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.dbide.app.ui.VaultScreen
import dev.dbide.app.ui.VaultUnavailableScreen
import dev.dbide.app.ui.WorkspaceScreen
import dev.dbide.core.appdata.AppPaths
import dev.dbide.core.connections.ConnectionService
import dev.dbide.core.connections.DefaultConnectionService
import dev.dbide.core.registry.ConnectionRegistry
import dev.dbide.core.store.ConfigStore
import dev.dbide.core.vault.Vault
import java.awt.Dimension
import java.lang.management.ManagementFactory
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

    Window(
        onCloseRequest = ::exitApplication,
        title = "Database IDE",
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
    ) {
        window.minimumSize = Dimension(760, 480)
        ReportColdStart()
        MaterialTheme {
            when (val state = startup) {
                // The first frame, before the configuration database has been opened.
                Startup.Opening -> Unit
                is Startup.Failed -> VaultUnavailableScreen(VaultUiState.Unavailable(state.failure))
                is Startup.Ready -> Workspace(state.value.service, scope)
            }
        }
    }
}

/**
 * The locked/unlocked split. Both view models live for the window's lifetime, so
 * locking and unlocking again does not lose the list or leak a scope.
 */
@Composable
private fun Workspace(service: ConnectionService, scope: CoroutineScope) {
    val connections = remember(service) { ConnectionsViewModel(service, scope) }
    val vault = remember(service) {
        VaultViewModel(service, scope, onUnlocked = { connections.refresh() })
    }

    LaunchedEffect(vault) { vault.load() }

    when (val screen = vault.screen) {
        is VaultUiState.Unavailable -> VaultUnavailableScreen(screen)
        VaultUiState.Loading -> Unit
        VaultUiState.Setup, VaultUiState.Locked -> VaultScreen(vault)
        VaultUiState.Unlocked -> WorkspaceScreen(
            viewModel = connections,
            onLock = {
                connections.clear()
                vault.lock()
            },
        )
    }
}

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
