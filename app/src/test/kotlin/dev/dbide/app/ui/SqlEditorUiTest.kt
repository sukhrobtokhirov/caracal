package dev.dbide.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.EditorViewModel
import dev.dbide.app.FakeConnectionService
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.core.catalog.ColumnInfo
import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import dev.dbide.core.result.QueryResult
import dev.dbide.core.vault.VaultState
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test

/**
 * The editor, driven through the real composables.
 *
 * These are the assertions a view-model test cannot make: that the Run button is
 * wired to the statement the caret is in, that the chord runs the statement instead
 * of typing a newline into it, that a running query can be stopped from the toolbar,
 * and that a name double-clicked in the object browser arrives in the script.
 */
@OptIn(ExperimentalTestApi::class)
class SqlEditorUiTest {

    /**
     * The connection the editor points at: writable, non-production.
     *
     * Which is to say, one where Run sends the statement. §2.4's dialog has its own
     * file; these tests are about the editor, and a confirmation in front of every
     * one of them would be testing the wrong thing twice.
     */
    private fun connection(
        environment: Environment = Environment.DEV,
        readOnly: Boolean = false,
    ) = ConnectionConfig(
        id = ConnectionId("id-1"),
        name = "local",
        engine = Engine.POSTGRES,
        host = "localhost",
        port = 5432,
        database = "dbide",
        username = "dbide",
        tlsMode = TlsMode.DISABLE,
        environment = environment,
        readOnly = readOnly,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        queryResult = QueryResult(
            columns = listOf(Column("total", "int8", ColumnFormat.NUMBER)),
            rows = listOf(listOf(CellValue.Integer(42))),
            duration = 5.milliseconds,
        )
    }

    /** Mounts the query pane on its own and hands back the model the test can read. */
    private fun ComposeUiTest.pane(
        service: FakeConnectionService,
        connection: ConnectionConfig = connection(),
    ): EditorViewModel {
        lateinit var model: EditorViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { EditorViewModel(service, scope).also { it.show(connection) } }
            MaterialTheme { QueryPane(model, onCopy = {}) }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.type(sql: String) {
        onNodeWithContentDescription("editor-text").performTextInput(sql)
        waitForIdle()
    }

    // --- Running --------------------------------------------------------------

    @Test
    fun `Run sends the statement at the caret and the grid shows its result`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service)
            type("select total from invoices;")

            onNodeWithContentDescription("editor-run-label").assertTextEquals(
                "Runs the statement at the caret.",
            )
            onNodeWithContentDescription("editor-run").performClick()
            waitForIdle()

            assertEquals(listOf("select total from invoices;"), service.executed)
            onNodeWithContentDescription("result-grid").assertIsDisplayed()
            onNodeWithContentDescription("grid-cell-0-0").assertTextEquals("42")
        }

    @Test
    fun `the chord runs the statement instead of typing into it`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            val model = pane(service)
            type("select 1;")

            onNodeWithContentDescription("editor-text").performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) }
            }
            waitForIdle()

            assertEquals(listOf("select 1;"), service.executed)
            assertEquals("select 1;", model.text.text)
        }

    @Test
    fun `Run is disabled while there is nothing to run`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            pane(service())

            onNodeWithContentDescription("query-idle").assertIsDisplayed()
            onNodeWithContentDescription("editor-run").assertIsNotEnabled()

            type("-- a note, and no statement")
            onNodeWithContentDescription("editor-run").assertIsNotEnabled()
            onNodeWithContentDescription("editor-run-label").assertTextEquals("There is nothing to run.")

            type("\nselect 1;")
            onNodeWithContentDescription("editor-run").assertIsEnabled()
        }

    @Test
    fun `a selection of two statements is refused in the toolbar rather than sent`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service)
            type("select 1; select 2;")

            onNodeWithContentDescription("editor-text").performTextInputSelection(TextRange(0, 19))
            waitForIdle()

            onNodeWithContentDescription("editor-run").assertIsNotEnabled()
            onNodeWithContentDescription("editor-run-label").assertTextEquals(
                "The selection holds more than one statement. Select one of them, or put the caret in it.",
            )
            assertEquals(emptyList(), service.executed)
        }

    @Test
    fun `the script is drawn with its gutter, highlighting and all`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            pane(service())
            type("-- a note\nselect 'x', 1.5 from \"T\" where id = \$1;\nselect 2;")

            // Capturing forces a real draw pass, which is the only way the gutter's
            // canvas and the styled text are exercised rather than merely composed.
            onNodeWithContentDescription("editor-gutter").captureToImage()
            onNodeWithContentDescription("sql-editor").captureToImage()
        }

    // --- Cancelling -----------------------------------------------------------

    @Test
    fun `a running statement can be cancelled from the toolbar`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            service.gate = CompletableDeferred()
            pane(service)
            type("select pg_sleep(30);")

            onNodeWithContentDescription("editor-run").performClick()
            waitForIdle()
            onNodeWithContentDescription("query-running").assertIsDisplayed()
            onNodeWithContentDescription("editor-run").assertIsNotEnabled()

            onNodeWithContentDescription("editor-cancel").performClick()
            waitForIdle()

            // Cancelled says so plainly, and is not drawn as a failure.
            onNodeWithContentDescription("query-cancelled").assertIsDisplayed()
            onNodeWithContentDescription("editor-run").assertIsEnabled()
            service.gate?.complete(Unit)
        }

    // --- Failing --------------------------------------------------------------

    @Test
    fun `a rejected statement shows the error the server gave`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            service.nextFailure = DbException(DbError.QueryFailed("column \"nope\" does not exist"))
            pane(service)
            // A statement the policy grants without asking, so what is asserted is the
            // reply and not the dialog. §2.4's gate has its own file.
            type("select nope;")

            onNodeWithContentDescription("editor-run").performClick()
            waitForIdle()

            onNodeWithContentDescription("failure-query_failed").assertIsDisplayed()
        }

    // --- With the rest of the workspace ---------------------------------------

    @Test
    fun `a name double-clicked in the browser lands in the script`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = service()
            service.seed(name = "Live", status = RuntimeStatus.OPEN)
            service.seedObject(
                schema = "public",
                name = "users",
                columns = listOf(ColumnInfo(1, "id", "int8", nullable = false)),
            )
            lateinit var editor: EditorViewModel
            setContent {
                val scope = rememberCoroutineScope()
                val connections = remember { ConnectionsViewModel(service, scope) }
                val tree = remember { SchemaTreeViewModel(service, scope) }
                editor = remember { EditorViewModel(service, scope) }
                MaterialTheme { WorkspaceScreen(connections, tree, editor, onLock = {}) }
            }
            waitForIdle()

            onNodeWithContentDescription("connection-Live").performClick()
            waitForIdle()
            // The editor is the pane an open connection lands on.
            onNodeWithContentDescription("sql-editor").assertIsDisplayed()

            onNodeWithContentDescription("editor-text").performTextInput("select * from")
            onNodeWithContentDescription("node-schema-public").performClick()
            waitForIdle()
            onNodeWithContentDescription("node-folder-public-table").performClick()
            waitForIdle()
            onNodeWithContentDescription("node-object-public-users").performMouseInput { doubleClick() }
            waitForIdle()

            assertEquals("select * from \"public\".\"users\"", editor.text.text)
        }

    @Test
    fun `a closed connection has no editor`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = service()
            service.seed(name = "Closed", status = RuntimeStatus.CLOSED)
            setContent {
                val scope = rememberCoroutineScope()
                val connections = remember { ConnectionsViewModel(service, scope) }
                val tree = remember { SchemaTreeViewModel(service, scope) }
                val editor = remember { EditorViewModel(service, scope) }
                MaterialTheme { WorkspaceScreen(connections, tree, editor, onLock = {}) }
            }
            waitForIdle()

            onNodeWithContentDescription("connection-Closed").performClick()
            waitForIdle()

            onNodeWithContentDescription("sql-editor").assertDoesNotExist()
        }
}
