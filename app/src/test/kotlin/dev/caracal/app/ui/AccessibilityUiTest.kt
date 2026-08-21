package dev.caracal.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.EditorViewModel
import dev.caracal.app.ExportViewModel
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.SchemaTreeViewModel
import dev.caracal.app.Shortcuts
import dev.caracal.core.catalog.Listing
import dev.caracal.core.catalog.SchemaInfo
import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.ColumnFormat
import dev.caracal.core.result.QueryResult
import dev.caracal.core.vault.VaultState
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

/**
 * What the application tells something that is reading it out rather than drawing it.
 *
 * These are §4.9's assertions of last resort. Every one of them is invisible on
 * screen, which is exactly why it needs a test: a missing state, a glyph announced by
 * its Unicode name, or a query that finishes in silence all look perfect in a
 * screenshot.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilityUiTest {

    private fun stateIs(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    // --- The editor -----------------------------------------------------------

    private fun connection() = ConnectionConfig(
        id = ConnectionId("id-1"),
        name = "Local",
        engine = Engine.POSTGRES,
        host = "localhost",
        port = 5432,
        database = "app",
        username = "caracal",
        environment = Environment.DEV,
        readOnly = true,
        tlsMode = TlsMode.DISABLE,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    private fun ComposeUiTest.pane(): FakeConnectionService {
        val service = FakeConnectionService(VaultState.UNLOCKED).apply {
            queryResult = QueryResult(
                columns = listOf(Column("total", "int8", ColumnFormat.NUMBER)),
                rows = listOf(listOf(CellValue.Integer(42))),
                duration = 5.milliseconds,
            )
        }
        setContent {
            val scope = rememberCoroutineScope()
            val model = remember { EditorViewModel(service, scope).also { it.show(connection()) } }
            val export = remember { ExportViewModel(service, scope) { null } }
            CaracalTheme {
                QueryPane(model, export, onCopy = {}, shortcuts = remember { Shortcuts() })
            }
        }
        waitForIdle()
        return service
    }

    @Test
    fun `a finished query says so out loud, without reading the result`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            pane()

            // Nothing to announce before anything has been run.
            onNodeWithTag("run-announcement").assertDoesNotExist()

            onNodeWithTag("editor-text").performTextInput("select 42")
            waitForIdle()
            onNodeWithTag("editor-run").performClick()
            waitForIdle()

            onNodeWithTag("run-announcement")
                .assertContentDescriptionEquals("Finished. 1 row · 5.00 ms")
        }

    @Test
    fun `a cell says which column it is in`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            pane()
            onNodeWithTag("editor-text").performTextInput("select 42")
            waitForIdle()
            onNodeWithTag("editor-run").performClick()
            waitForIdle()

            // Not "42". The header is scrolled off to the left of a wide result, and
            // a value with no column name is a value with no subject.
            onNodeWithTag("grid-cell-0-0").assertContentDescriptionEquals("total, 42")
        }

    // --- The tree -------------------------------------------------------------

    private fun ComposeUiTest.tree() {
        val service = FakeConnectionService(VaultState.UNLOCKED).apply {
            schemas = Listing(listOf(SchemaInfo("public", owner = "caracal")))
            seedObject(schema = "public", name = "users")
        }
        setContent {
            val scope = rememberCoroutineScope()
            val model = remember { SchemaTreeViewModel(service, scope) }
            LaunchedEffect(Unit) { model.show(ConnectionId("id-1")) }
            CaracalTheme { SchemaTree(model, TreeActions(insert = {}, copy = {})) }
        }
        waitForIdle()
    }

    @Test
    fun `a tree row says whether it is open, and its triangle says what it does`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            tree()

            onNodeWithTag("node-schema-public").assert(stateIs("Collapsed"))
            // `▸` announces as "black right-pointing small triangle" if left alone.
            onNodeWithTag("node-schema-public-toggle", useUnmergedTree = true)
                .assertContentDescriptionEquals("Expand")

            onNodeWithTag("node-schema-public-toggle", useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithTag("node-schema-public").assert(stateIs("Expanded"))
            onNodeWithTag("node-schema-public-toggle", useUnmergedTree = true)
                .assertContentDescriptionEquals("Collapse")
        }
}
