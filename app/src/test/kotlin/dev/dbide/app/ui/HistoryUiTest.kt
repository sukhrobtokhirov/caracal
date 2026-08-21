package dev.dbide.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.EditorViewModel
import dev.dbide.app.ExportViewModel
import dev.dbide.app.FakeConnectionService
import dev.dbide.app.HistoryViewModel
import dev.dbide.app.RedisWorkspace
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.ThemeViewModel
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.history.ExecutionOutcome
import dev.dbide.core.history.ExecutionRecord
import dev.dbide.core.vault.VaultState
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

/**
 * The history window, driven through the workspace that opens it.
 *
 * The assertions worth having here are the ones about what the window *will not* do.
 * Reopening a statement never runs it, and it never puts one server's query into
 * another server's editor — the first is why a panel of `DELETE`s is safe to browse,
 * and the second is why it is safe to browse with production open beside it.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryUiTest {

    private var next = 1L

    private fun record(
        statement: String,
        connection: String = "id-1",
        outcome: ExecutionOutcome = ExecutionOutcome.OK,
        at: String = "2026-08-21T09:00:00Z",
        error: String? = null,
    ) = ExecutionRecord(
        connectionId = ConnectionId(connection),
        statement = statement,
        outcome = outcome,
        executedAt = Instant.parse(at),
        duration = 8.milliseconds,
        rowCount = if (outcome == ExecutionOutcome.OK) 3 else null,
        error = error,
        id = next++,
    )

    private lateinit var editor: EditorViewModel

    private fun ComposeUiTest.workspace(service: FakeConnectionService) {
        setContent {
            val scope = rememberCoroutineScope()
            val connections = remember { ConnectionsViewModel(service, scope) }
            val tree = remember { SchemaTreeViewModel(service, scope) }
            editor = remember { EditorViewModel(service, scope) }
            val export = remember { ExportViewModel(service, scope) { null } }
            val theme = remember { ThemeViewModel(null, scope) }
            val redis = remember { RedisWorkspace(service, scope) }
            val history = remember { HistoryViewModel(service, scope) }
            DbideTheme {
                WorkspaceScreen(connections, tree, editor, export, redis, history, theme, onLock = {})
            }
        }
        waitForIdle()
    }

    /** Opens the window on a connection that is selected and open. */
    private fun ComposeUiTest.openHistory(service: FakeConnectionService, connection: String = "Local") {
        workspace(service)
        onNodeWithContentDescription("connection-$connection").performClick()
        waitForIdle()
        onNodeWithContentDescription("open-history").performClick()
        waitForIdle()
    }

    @Test
    fun `the window opens on the selected connection and groups what it shows by day`() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local", status = RuntimeStatus.OPEN)
            service.seed(name = "Other")
            service.history += listOf(
                record("select * from invoices", at = "2026-08-20T09:00:00Z"),
                record("select count(*) from customers"),
                record("select from other", connection = "id-2"),
            )

            openHistory(service)

            onNodeWithContentDescription("history-window").assertIsDisplayed()
            // Filtered to the connection the user was looking at, so the other
            // server's statement is not on screen.
            onNodeWithText("select count(*) from customers").assertIsDisplayed()
            onNodeWithText("select from other").assertDoesNotExist()
            // Two days, two headings.
            assertEquals(2, onAllNodesWithContentDescription("history-day").fetchSemanticsNodes().size)
        }

    @Test
    fun `an entry opens to show the whole statement and the message it failed with`() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local", status = RuntimeStatus.OPEN)
            service.history += record(
                statement = "select *\nfrom invoice",
                outcome = ExecutionOutcome.ERROR,
                error = "relation \"invoice\" does not exist",
            )

            openHistory(service)
            // Collapsed, the preview is one line: the newlines are gone.
            onNodeWithContentDescription("history-statement")
                .assertTextContains("select * from invoice")
            onNodeWithContentDescription("history-statement").performClick()
            waitForIdle()

            // Expanded, it is the statement exactly as it was submitted — which is
            // the form someone is about to compare against what is in their editor.
            onNodeWithContentDescription("history-statement")
                .assertTextContains("select *\nfrom invoice")
            onNodeWithContentDescription("history-error")
                .assertTextContains("relation \"invoice\" does not exist")
        }

    @Test
    fun `reopening puts the statement in the editor and does not run it`() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local", status = RuntimeStatus.OPEN)
            service.history += record("delete from invoices where id = 7")

            openHistory(service)
            onNodeWithContentDescription("history-statement").performClick()
            waitForIdle()
            onNodeWithContentDescription("history-open").performClick()
            waitForIdle()

            assertEquals("delete from invoices where id = 7", editor.text.text)
            // The whole point of the rule: a menu line that fired a `DELETE` at
            // production the moment it was clicked is a line people learn not to
            // click. Run is where that decision lives, and it has not been pressed.
            assertEquals(emptyList(), service.executed)
            onNodeWithContentDescription("history-window").assertDoesNotExist()
        }

    @Test
    fun `a statement from another server cannot be dropped into this one's editor`() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local", status = RuntimeStatus.OPEN)
            service.seed(name = "Prod")
            service.history += record("select * from ledger", connection = "id-2")

            openHistory(service)
            onNodeWithContentDescription("history-filter-all").performClick()
            waitForIdle()
            onNodeWithContentDescription("history-statement").performClick()
            waitForIdle()

            // Refused, and the row says which connection would have to be open. An
            // editor that silently retargeted would be one server's query sent to
            // another server's database.
            onNodeWithContentDescription("history-open").assertIsNotEnabled()
            onNode(hasText("Open the connection this ran on to put it back in an editor."))
                .assertIsDisplayed()
        }

    @Test
    fun `a reopened statement asks before it replaces a script in progress`() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local", status = RuntimeStatus.OPEN)
            service.history += record("select now()")
            workspace(service)
            onNodeWithContentDescription("connection-Local").performClick()
            waitForIdle()
            onNodeWithContentDescription("editor-text").performTextInput("select 1")
            waitForIdle()

            onNodeWithContentDescription("open-history").performClick()
            waitForIdle()
            onNodeWithContentDescription("history-statement").performClick()
            waitForIdle()
            onNodeWithContentDescription("history-open").performClick()
            waitForIdle()

            onNodeWithContentDescription("replace-script-confirmation").assertIsDisplayed()
            assertEquals("select 1", editor.text.text)

            onNodeWithContentDescription("confirm-replace-script").performClick()
            waitForIdle()

            assertEquals("select now()", editor.text.text)
        }

    @Test
    fun `clearing asks first, names what goes, and leaves the connections alone`() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local", status = RuntimeStatus.OPEN)
            service.history += record("select now()")

            openHistory(service)
            onNodeWithContentDescription("history-clear-connection").performClick()
            waitForIdle()

            onNode(hasText("Clear the history for \"Local\"?")).assertIsDisplayed()
            onNodeWithContentDescription("confirm-clear-history").performClick()
            waitForIdle()

            assertEquals(emptyList(), service.history)
            onNodeWithContentDescription("history-empty").assertIsDisplayed()
            // The connection itself is untouched, and is still in the sidebar.
            onNodeWithContentDescription("connection-Local").assertIsDisplayed()
        }

    @Test
    fun `a search that matches nothing says it searched what is loaded`() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Local", status = RuntimeStatus.OPEN)
            service.history += record("select now()")

            openHistory(service)
            onNodeWithContentDescription("history-search").performTextInput("invoices")
            waitForIdle()

            // Not "no history yet". The user has just typed something; telling them
            // their queries will appear here once they run some is how a panel
            // teaches someone to stop reading it.
            onNodeWithContentDescription("history-empty-search").assertIsDisplayed()
            onNodeWithContentDescription("history-empty").assertDoesNotExist()
        }

    @Test
    fun `a machine that has run nothing says so rather than showing a blank pane`() =
        runDesktopComposeUiTest(width = 1500, height = 1000) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Cache", engine = Engine.REDIS, status = RuntimeStatus.OPEN)

            openHistory(service, connection = "Cache")

            // A connection with no history is not a filter that needs changing: it is
            // the ordinary "nothing has been run yet", and it gets the sentence that
            // says where history lives.
            onNodeWithContentDescription("history-empty").assertIsDisplayed()
        }
}
