package dev.dbide.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.EditorTabs
import dev.dbide.app.HistoryViewModel
import dev.dbide.app.FakeConnectionService
import dev.dbide.app.RedisWorkspace
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.ThemeViewModel
import dev.dbide.app.VaultUiState
import dev.dbide.app.VaultViewModel
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.connections.SecretUpdate
import dev.dbide.core.vault.VaultState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The milestone's acceptance scenario, driven through the real composables.
 *
 * These are the assertions that would still pass if the view models were correct
 * and the screen was not: that the production warning is on screen, that the saved
 * password is never in a field, and that deleting asks first.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionUiTest {

    /** Mounts the vault screen with a real view model behind it. */
    private fun ComposeUiTest.vaultScreen(service: FakeConnectionService): VaultViewModel {
        lateinit var model: VaultViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { VaultViewModel(service, scope) }
            val theme = remember { ThemeViewModel(null, scope) }
            LaunchOnce { model.load() }
            DbideTheme {
                if (model.screen == VaultUiState.Unlocked) {
                    Text("unlocked")
                } else {
                    VaultScreen(model, theme)
                }
            }
        }
        waitForIdle()
        return model
    }

    /** Mounts the workspace with a real view model behind it. */
    private fun ComposeUiTest.workspace(service: FakeConnectionService): ConnectionsViewModel {
        lateinit var model: ConnectionsViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { ConnectionsViewModel(service, scope) }
            val tree = remember { SchemaTreeViewModel(service, scope) }
            val tabs = remember { EditorTabs(service, scope) { null } }
            val theme = remember { ThemeViewModel(null, scope) }
            val redis = remember { RedisWorkspace(service, scope) }
            val history = remember { HistoryViewModel(service, scope) }
            DbideTheme {
                WorkspaceScreen(model, tree, tabs, redis, history, theme, onLock = {})
            }
        }
        waitForIdle()
        return model
    }

    // --- Setup and unlock ----------------------------------------------------

    @Test
    fun `first run asks the user to choose a master password and says it cannot be recovered`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            vaultScreen(FakeConnectionService(VaultState.SETUP_REQUIRED))

            onNodeWithText("Choose a master password").assertIsDisplayed()
            onNodeWithText("It encrypts every database password you save.", substring = true)
                .assertIsDisplayed()
            onNodeWithContentDescription("confirm-master-password").assertIsDisplayed()
            onNodeWithContentDescription("vault-submit").assertIsNotEnabled()
        }

    @Test
    fun `a returning user is asked only to unlock`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        vaultScreen(FakeConnectionService(VaultState.LOCKED))

        onNodeWithText("Unlock Database IDE").assertIsDisplayed()
        onNodeWithContentDescription("confirm-master-password").assertDoesNotExist()
    }

    @Test
    fun `setting a master password moves the application into the workspace`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.SETUP_REQUIRED)
        vaultScreen(service)

        onNodeWithContentDescription("master-password").performTextInput("correct-horse")
        onNodeWithContentDescription("confirm-master-password").performTextInput("correct-horse")
        onNodeWithContentDescription("vault-submit").performClick()
        waitUntil { service.calls.contains("setUp") }
        waitForIdle()

        onNodeWithText("unlocked").assertIsDisplayed()
    }

    @Test
    fun `a wrong master password says only that, and stays on the unlock screen`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.LOCKED)
        vaultScreen(service)

        onNodeWithContentDescription("master-password").performTextInput("not-the-password")
        onNodeWithContentDescription("vault-submit").performClick()
        waitUntil { service.calls.contains("unlock") }
        waitForIdle()

        onNodeWithContentDescription("failure-wrong_password").assertIsDisplayed()
        onNodeWithText("Unlock Database IDE").assertIsDisplayed()
    }

    // --- The connection list -------------------------------------------------

    @Test
    fun `an empty workspace explains what to do next`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        workspace(FakeConnectionService(VaultState.UNLOCKED))

        onNodeWithContentDescription("connections-empty").assertIsDisplayed()
        onNodeWithContentDescription("workspace-empty").assertIsDisplayed()
    }

    @Test
    fun `a production connection is labelled PROD in the list, not merely coloured`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "prod-db", environment = Environment.PROD, readOnly = true)
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("connection-prod-db").assertIsDisplayed()
            // The badge is a word, so it survives a colour-blind user and a screenshot.
            onNodeWithContentDescription("environment-prod").assertExists()
            onNodeWithContentDescription("read-only").assertExists()
        }

    @Test
    fun `selecting a production connection turns the application shell red and names it`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            val saved = service.seed(name = "prod-db", environment = Environment.PROD)
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("shell-normal").assertExists()

            onNodeWithContentDescription("connection-prod-db").performClick()
            waitForIdle()

            // The warning lives in the shell, so it cannot scroll away.
            onNodeWithContentDescription("shell-prod").assertExists()
            assertEquals(saved.id, model.selected?.id)
        }

    @Test
    fun `hiding the sidebar takes the list off screen, and the shell brings it back`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local")
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("collapse-sidebar").performClick()
            waitForIdle()

            onNodeWithContentDescription("connection-Local").assertDoesNotExist()

            // The pane is gone; the way back to it is not.
            onNodeWithContentDescription("toggle-sidebar").performClick()
            waitForIdle()

            onNodeWithContentDescription("connection-Local").assertIsDisplayed()
        }

    @Test
    fun `double-clicking a connection opens it`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        val saved = service.seed(name = "Local")
        val model = workspace(service)
        waitUntil { model.connections.isNotEmpty() }

        onNodeWithContentDescription("connection-Local").performMouseInput { doubleClick() }
        waitUntil { model.connections.single().runtime.status == RuntimeStatus.OPEN }
        waitForIdle()

        // Opened, and selected: the shell has to be naming the server that was opened.
        assertEquals(saved.id, model.selected?.id)
        onNodeWithContentDescription("sql-editor").assertIsDisplayed()
    }

    @Test
    fun `right-clicking a connection offers its actions and acts on that row`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            val saved = service.seed(name = "Local")
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("connection-Local").performMouseInput { rightClick() }
            waitForIdle()

            // Right-clicking selects, so the menu and the rest of the window agree
            // about which connection is being acted on.
            assertEquals(saved.id, model.selected?.id)
            onNodeWithContentDescription("menu-test").assertIsDisplayed()
            onNodeWithContentDescription("menu-delete").assertIsDisplayed()

            onNodeWithContentDescription("menu-open").performClick()
            waitUntil { model.connections.single().runtime.status == RuntimeStatus.OPEN }
            waitForIdle()

            // The menu closes behind the action, and now offers the other half of it.
            onNodeWithContentDescription("menu-open").assertDoesNotExist()
            onNodeWithContentDescription("connection-Local").performMouseInput { rightClick() }
            waitForIdle()

            onNodeWithContentDescription("menu-close").assertIsDisplayed()
        }

    @Test
    fun `deleting from the context menu still asks first`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "prod-db", environment = Environment.PROD)
        val model = workspace(service)
        waitUntil { model.connections.isNotEmpty() }

        onNodeWithContentDescription("connection-prod-db").performMouseInput { rightClick() }
        onNodeWithContentDescription("menu-delete").performClick()
        waitForIdle()

        assertFalse(service.calls.contains("delete"))
        onNodeWithContentDescription("delete-confirmation").assertExists()
        onNodeWithText("This is a PROD connection.").assertIsDisplayed()
    }

    // --- The windows ---------------------------------------------------------

    @Test
    fun `creating a connection opens a window over the workspace, and cancelling closes it`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local")
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("new-connection").performClick()
            waitForIdle()

            onNodeWithContentDescription("new-connection-dialog").assertExists()
            // The window is over the workspace, not instead of it: the sidebar the
            // user was reading is still there behind it.
            onNodeWithContentDescription("connection-Local").assertIsDisplayed()

            onNodeWithContentDescription("cancel-form").performClick()
            waitForIdle()

            onNodeWithContentDescription("new-connection-dialog").assertDoesNotExist()
            assertTrue(service.drafts.isEmpty())
        }

    @Test
    fun `opening the New window does not empty the workspace behind it`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local")
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("connection-Local").performMouseInput { doubleClick() }
            waitUntil { model.connections.single().runtime.status == RuntimeStatus.OPEN }
            waitForIdle()

            onNodeWithContentDescription("new-connection").performClick()
            waitForIdle()

            // Nothing is selected while a new connection is being created, and the
            // editor the user was working in stays on screen regardless.
            onNodeWithContentDescription("new-connection-dialog").assertExists()
            onNodeWithContentDescription("sql-editor").assertIsDisplayed()
            onNodeWithContentDescription("schema-tree").assertIsDisplayed()
        }

    @Test
    fun `the engine is chosen in the window's rail, and the form follows it`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            val model = workspace(service)

            onNodeWithContentDescription("new-connection").performClick()
            onNodeWithContentDescription("field-name").performTextInput("Cache")
            onNodeWithContentDescription("field-host").performTextInput("localhost")
            onNodeWithContentDescription("engine-choice-redis").performClick()
            waitForIdle()

            // Choosing the engine is what fills in its port and its database index,
            // so the one click is the whole decision.
            onNodeWithContentDescription("save-connection").performClick()
            waitUntil { model.connections.isNotEmpty() }

            val draft = service.drafts.single()
            assertEquals(Engine.REDIS, draft.engine)
            assertEquals(6379, draft.port)
            assertEquals("0", draft.database)
        }

    @Test
    fun `editing opens a window, and the connection it is editing is still behind it`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local", hasSecret = true)
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("connection-Local").performClick()
            onNodeWithContentDescription("edit-connection").performClick()
            waitForIdle()

            onNodeWithContentDescription("edit-connection-dialog").assertExists()
            // The detail pane underneath is still the one being edited, so the window
            // is a question about something the user can still see.
            onNodeWithContentDescription("test-connection").assertIsDisplayed()
        }

    @Test
    fun `the theme is chosen in the settings window rather than cycled in the shell`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            lateinit var theme: ThemeViewModel
            setContent {
                val scope = rememberCoroutineScope()
                val model = remember { ConnectionsViewModel(service, scope) }
                val tree = remember { SchemaTreeViewModel(service, scope) }
                val tabs = remember { EditorTabs(service, scope) { null } }
                theme = remember { ThemeViewModel(null, scope) }
                val redis = remember { RedisWorkspace(service, scope) }
                val history = remember { HistoryViewModel(service, scope) }
                DbideTheme(theme.mode) {
                    WorkspaceScreen(model, tree, tabs, redis, history, theme, onLock = {})
                }
            }
            waitForIdle()

            assertEquals(ThemeMode.DARK, theme.mode)

            onNodeWithContentDescription("open-settings").performClick()
            waitForIdle()
            onNodeWithContentDescription("settings-dialog").assertExists()

            onNodeWithContentDescription("theme-choice-light").performClick()
            waitForIdle()

            // Named rather than cycled: the click lands on the theme that was wanted.
            assertEquals(ThemeMode.LIGHT, theme.mode)

            onNodeWithContentDescription("settings-done").performClick()
            waitForIdle()

            onNodeWithContentDescription("settings-dialog").assertDoesNotExist()
        }

    // --- Creating ------------------------------------------------------------

    @Test
    fun `creating a connection saves what was typed`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        val model = workspace(service)

        onNodeWithContentDescription("new-connection").performClick()
        onNodeWithContentDescription("field-name").performTextInput("Local")
        onNodeWithContentDescription("field-host").performTextInput("localhost")
        onNodeWithContentDescription("field-database").performTextInput("dbide")
        onNodeWithContentDescription("field-password").performTextInput("hunter2")
        onNodeWithContentDescription("save-connection").performClick()
        waitUntil { model.connections.isNotEmpty() }

        val draft = service.drafts.single()
        assertEquals("Local", draft.name)
        assertEquals("localhost", draft.host)
        assertTrue(draft.secret is SecretUpdate.Replace)
    }

    @Test
    fun `a form with nothing in it shows what is missing rather than saving`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        workspace(service)

        onNodeWithContentDescription("new-connection").performClick()
        onNodeWithContentDescription("save-connection").performClick()
        waitForIdle()

        assertFalse(service.calls.contains("create"))
        onNodeWithText("A name is required.").assertIsDisplayed()
        onNodeWithText("A host is required.").assertIsDisplayed()
    }

    @Test
    fun `choosing Redis offers only the TLS modes Redis has`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        workspace(FakeConnectionService(VaultState.UNLOCKED))

        onNodeWithContentDescription("new-connection").performClick()
        onNodeWithContentDescription("tls-choice-verify-full").assertExists()

        onNodeWithContentDescription("engine-choice-redis").performClick()
        waitForIdle()

        onNodeWithContentDescription("tls-choice-verify-full").assertDoesNotExist()
        onNodeWithContentDescription("tls-choice-require").assertExists()
    }

    @Test
    fun `marking a connection production warns before it is even saved`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        workspace(FakeConnectionService(VaultState.UNLOCKED))

        onNodeWithContentDescription("new-connection").performClick()
        onNodeWithContentDescription("environment-choice-prod").performClick()
        waitForIdle()

        onNodeWithContentDescription("prod-warning").assertIsDisplayed()
    }

    // --- Editing -------------------------------------------------------------

    @Test
    fun `editing shows that the saved password is being left alone, and never shows it`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local")
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("connection-Local").performClick()
            onNodeWithContentDescription("edit-connection").performClick()
            waitForIdle()

            onNodeWithText("Leave saved password unchanged").assertIsDisplayed()
            // There is no password field at all until the user asks for one.
            onNodeWithContentDescription("field-password").assertDoesNotExist()
        }

    @Test
    fun `an edit that touches only a colour leaves the stored password alone`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "Local")
        val model = workspace(service)
        waitUntil { model.connections.isNotEmpty() }

        onNodeWithContentDescription("connection-Local").performClick()
        onNodeWithContentDescription("edit-connection").performClick()
        onNodeWithContentDescription("field-color").performTextInput("#ff8800")
        onNodeWithContentDescription("save-connection").performClick()
        waitUntil { service.calls.contains("update") }

        assertEquals(SecretUpdate.Unchanged, service.drafts.single().secret)
        assertTrue(model.connections.single().hasSecret)
    }

    @Test
    fun `asking to replace the password reveals an empty field, never the saved one`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local")
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("connection-Local").performClick()
            onNodeWithContentDescription("edit-connection").performClick()
            onNodeWithContentDescription("replace-password").performClick()
            waitForIdle()

            onNodeWithContentDescription("field-password").assertIsDisplayed()
            onNodeWithText("Leave this empty to remove the saved password.").assertIsDisplayed()
        }

    // --- Detail, test, open, delete ------------------------------------------

    @Test
    fun `the detail pane reports that a password is saved without showing it`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "Local", hasSecret = true)
        val model = workspace(service)
        waitUntil { model.connections.isNotEmpty() }

        onNodeWithContentDescription("connection-Local").performClick()
        waitForIdle()

        onNodeWithText("Saved").assertIsDisplayed()
        onNodeWithText("hunter2").assertDoesNotExist()
    }

    @Test
    fun `testing a connection reports what it reached`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "Local")
        val model = workspace(service)
        waitUntil { model.connections.isNotEmpty() }

        onNodeWithContentDescription("connection-Local").performClick()
        onNodeWithContentDescription("test-connection").performClick()
        waitUntil { model.testResult != null }
        waitForIdle()

        onNodeWithContentDescription("test-succeeded").assertIsDisplayed()
        onNodeWithText("Connected in 12 ms.").assertIsDisplayed()
    }

    @Test
    fun `opening a connection changes what the detail pane offers`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "Local")
        val model = workspace(service)
        waitUntil { model.connections.isNotEmpty() }

        onNodeWithContentDescription("connection-Local").performClick()
        onNodeWithContentDescription("open-connection").performClick()
        waitUntil { model.connections.single().runtime.status == RuntimeStatus.OPEN }
        waitForIdle()

        // An open connection lands on its editor, and its details are one tab away.
        onNodeWithContentDescription("sql-editor").assertIsDisplayed()
        onNodeWithContentDescription("workspace-tab-connection").performClick()
        waitForIdle()

        onNodeWithContentDescription("close-connection").assertIsDisplayed()
        onNodeWithContentDescription("open-connection").assertDoesNotExist()
    }

    @Test
    fun `deleting asks first, and cancelling leaves the connection alone`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "Local")
        val model = workspace(service)
        waitUntil { model.connections.isNotEmpty() }

        onNodeWithContentDescription("connection-Local").performClick()
        onNodeWithContentDescription("delete-connection").performClick()
        waitForIdle()

        onNodeWithContentDescription("delete-confirmation").assertExists()

        onNodeWithContentDescription("cancel-delete").performClick()
        waitForIdle()

        assertFalse(service.calls.contains("delete"))
        assertEquals(1, model.connections.size)
    }

    @Test
    fun `confirming a delete removes the connection`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "Local")
        val model = workspace(service)
        waitUntil { model.connections.isNotEmpty() }

        onNodeWithContentDescription("connection-Local").performClick()
        onNodeWithContentDescription("delete-connection").performClick()
        onNodeWithContentDescription("confirm-delete").performClick()
        waitUntil { model.connections.isEmpty() }

        assertTrue(service.calls.contains("delete"))
    }

    @Test
    fun `deleting a production connection says so in the confirmation`() = runDesktopComposeUiTest(width = 1400, height = 1600) {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "prod-db", environment = Environment.PROD)
        val model = workspace(service)
        waitUntil { model.connections.isNotEmpty() }

        onNodeWithContentDescription("connection-prod-db").performClick()
        onNodeWithContentDescription("delete-connection").performClick()
        waitForIdle()

        onNodeWithText("This is a PROD connection.").assertIsDisplayed()
    }

    @Test
    fun `a Redis connection is labelled by its engine and shows a database index`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Cache", engine = Engine.REDIS)
            val model = workspace(service)
            waitUntil { model.connections.isNotEmpty() }

            onNodeWithContentDescription("connection-Cache").performClick()
            waitForIdle()

            onNodeWithText("Database index").assertIsDisplayed()
        }
}

/** Runs [block] once when the composition first appears. */
@Composable
private fun LaunchOnce(block: () -> Unit) {
    LaunchedEffect(Unit) { block() }
}
