package dev.caracal.app.ui

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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import dev.caracal.app.ConnectionsViewModel
import dev.caracal.app.EditorTabs
import dev.caracal.app.EditorViewModel
import dev.caracal.app.ExportViewModel
import dev.caracal.app.HistoryViewModel
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.Platform
import dev.caracal.app.RedisWorkspace
import dev.caracal.app.SchemaTreeViewModel
import dev.caracal.app.Shortcuts
import dev.caracal.app.ThemeViewModel
import dev.caracal.core.catalog.ColumnInfo
import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.ColumnFormat
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.result.QueryResult
import dev.caracal.core.vault.VaultState
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
        database = "caracal",
        username = "caracal",
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

    /**
     * Mounts the query pane on its own and hands back the model the test can read.
     *
     * [platform] is named rather than read off the host, because the Run chord is one
     * of the few things in this application that is deliberately different on the two
     * — Meta on macOS, Control everywhere else — and a test that read the host would
     * assert whichever half the machine it ran on happened to have.
     */
    private fun ComposeUiTest.pane(
        service: FakeConnectionService,
        connection: ConnectionConfig = connection(),
        platform: Platform = Platform.MAC,
    ): EditorViewModel {
        lateinit var model: EditorViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { EditorViewModel(service, scope).also { it.show(connection) } }
            val export = remember { ExportViewModel(service, scope) { null } }
            CaracalTheme {
                QueryPane(
                    model,
                    export,
                    onCopy = {},
                    shortcuts = remember { Shortcuts(platform) },
                )
            }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.type(sql: String) {
        onNodeWithTag("editor-text").performTextInput(sql)
        waitForIdle()
    }

    // --- Running --------------------------------------------------------------

    @Test
    fun `Run sends the statement at the caret and the grid shows its result`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service)
            type("select total from invoices;")

            onNodeWithTag("editor-run-label").assertTextEquals(
                "Runs the statement at the caret.",
            )
            onNodeWithTag("editor-run").performClick()
            waitForIdle()

            assertEquals(listOf("select total from invoices;"), service.executed)
            onNodeWithTag("result-grid").assertIsDisplayed()
            onNodeWithTag("grid-cell-0-0").assertTextEquals("42")
        }

    @Test
    fun `the chord runs the statement instead of typing into it`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            val model = pane(service, platform = Platform.MAC)
            type("select 1;")

            onNodeWithTag("editor-text").performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) }
            }
            waitForIdle()

            assertEquals(listOf("select 1;"), service.executed)
            // And it did not also put a newline in the script.
            assertEquals("select 1;", model.text.text)
        }

    @Test
    fun `off macOS the same chord is Control, and Meta is left alone`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            val model = pane(service, platform = Platform.OTHER)
            type("select 1;")

            // Meta is the foreign modifier here. It must not run anything — and,
            // because the editor declines it, the field is free to do whatever it
            // would normally do with it.
            onNodeWithTag("editor-text").performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.Enter) }
            }
            waitForIdle()
            assertEquals(emptyList(), service.executed)

            onNodeWithTag("editor-text").performKeyInput {
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

            onNodeWithTag("query-idle").assertIsDisplayed()
            onNodeWithTag("editor-run").assertIsNotEnabled()

            type("-- a note, and no statement")
            onNodeWithTag("editor-run").assertIsNotEnabled()
            onNodeWithTag("editor-run-label").assertTextEquals("There is nothing to run.")

            type("\nselect 1;")
            onNodeWithTag("editor-run").assertIsEnabled()
        }

    @Test
    fun `a selection of two statements is refused in the toolbar rather than sent`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service)
            type("select 1; select 2;")

            onNodeWithTag("editor-text").performTextInputSelection(TextRange(0, 19))
            waitForIdle()

            onNodeWithTag("editor-run").assertIsNotEnabled()
            onNodeWithTag("editor-run-label").assertTextEquals(
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
            onNodeWithTag("editor-gutter").captureToImage()
            onNodeWithTag("sql-editor").captureToImage()
        }

    // --- Cancelling -----------------------------------------------------------

    @Test
    fun `a running statement can be cancelled from the toolbar`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            service.gate = CompletableDeferred()
            pane(service)
            type("select pg_sleep(30);")

            onNodeWithTag("editor-run").performClick()
            waitForIdle()
            onNodeWithTag("query-running").assertIsDisplayed()
            onNodeWithTag("editor-run").assertIsNotEnabled()

            onNodeWithTag("editor-cancel").performClick()
            waitForIdle()

            // Cancelled says so plainly, and is not drawn as a failure.
            onNodeWithTag("query-cancelled").assertIsDisplayed()
            onNodeWithTag("editor-run").assertIsEnabled()
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

            onNodeWithTag("editor-run").performClick()
            waitForIdle()

            onNodeWithTag("failure-query_failed").assertIsDisplayed()
        }

    @Test
    fun `editing the failed statement replaces the marker with a sentence saying why`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            service.nextFailure = DbException(
                DbError.QueryFailed("column \"totl\" does not exist", position = 8),
            )
            pane(service)
            type("select totl from invoices;")

            onNodeWithTag("editor-run").performClick()
            waitForIdle()

            // While the script still reads as it was sent, the banner says only what
            // the server said — the editor is doing the pointing.
            onNodeWithTag("failure-query_failed").assertIsDisplayed()
            onNodeWithTag("error-note").assertDoesNotExist()

            // Fixing the column moves every character after it. The underline goes,
            // and the reason it went is on screen rather than left to be inferred.
            onNodeWithTag("editor-text").performTextInputSelection(
                TextRange("select ".length, "select totl".length),
            )
            onNodeWithTag("editor-text").performTextInput("total")
            waitForIdle()

            onNodeWithTag("failure-query_failed").assertIsDisplayed()
            onNodeWithTag("error-note").assertIsDisplayed()
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
            lateinit var tabs: EditorTabs
            setContent {
                val scope = rememberCoroutineScope()
                val connections = remember { ConnectionsViewModel(service, scope) }
                val tree = remember { SchemaTreeViewModel(service, scope) }
                tabs = remember { EditorTabs(service, scope) { null } }
                val theme = remember { ThemeViewModel(null, scope) }
                val redis = remember { RedisWorkspace(service, scope) }
                val history = remember { HistoryViewModel(service, scope) }
                CaracalTheme {
                WorkspaceScreen(connections, tree, tabs, redis, history, theme, onLock = {})
            }
            }
            waitForIdle()

            onNodeWithTag("connection-Live").performClick()
            waitForIdle()
            // The editor is the pane an open connection lands on.
            onNodeWithTag("sql-editor").assertIsDisplayed()

            onNodeWithTag("editor-text").performTextInput("select * from")
            onNodeWithTag("node-schema-public").performClick()
            waitForIdle()
            onNodeWithTag("node-folder-public-table").performClick()
            waitForIdle()
            onNodeWithTag("node-object-public-users").performMouseInput { doubleClick() }
            waitForIdle()

            assertEquals("select * from \"public\".\"users\"", tabs.tabs.single().editor.text.text)
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
            val tabs = remember { EditorTabs(service, scope) { null } }
                val theme = remember { ThemeViewModel(null, scope) }
                val redis = remember { RedisWorkspace(service, scope) }
                val history = remember { HistoryViewModel(service, scope) }
                CaracalTheme {
                WorkspaceScreen(connections, tree, tabs, redis, history, theme, onLock = {})
            }
            }
            waitForIdle()

            onNodeWithTag("connection-Closed").performClick()
            waitForIdle()

            onNodeWithTag("sql-editor").assertDoesNotExist()
        }

    @Test
    fun `a connection that drops says the scripts are safe and offers to reconnect`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = service()
            val view = service.seed(name = "Live", status = RuntimeStatus.OPEN)
            lateinit var connections: ConnectionsViewModel
            lateinit var tabs: EditorTabs
            setContent {
                val scope = rememberCoroutineScope()
                connections = remember { ConnectionsViewModel(service, scope) }
                val tree = remember { SchemaTreeViewModel(service, scope) }
                tabs = remember { EditorTabs(service, scope) { null } }
                val theme = remember { ThemeViewModel(null, scope) }
                val redis = remember { RedisWorkspace(service, scope) }
                val history = remember { HistoryViewModel(service, scope) }
                CaracalTheme {
                    WorkspaceScreen(connections, tree, tabs, redis, history, theme, onLock = {})
                }
            }
            waitForIdle()

            onNodeWithTag("connection-Live").performClick()
            waitForIdle()
            onNodeWithTag("editor-text").performTextInput("select 1")
            waitForIdle()

            connections.close(view.id)
            waitForIdle()

            // §4.7: not a page of connection settings. The pane says what happened,
            // says the work is still here, and offers the one action that undoes it.
            onNodeWithTag("workspace-disconnected").assertIsDisplayed()
            onNodeWithTag("workspace-reconnect").assertIsEnabled()
            // The claim the pane is making, checked against the thing itself.
            assertEquals("select 1", tabs.tabs.single().editor.text.text)

            onNodeWithTag("workspace-reconnect").performClick()
            waitForIdle()

            onNodeWithTag("sql-editor").assertIsDisplayed()
            assertEquals("select 1", tabs.tabs.single().editor.text.text)
        }
}
