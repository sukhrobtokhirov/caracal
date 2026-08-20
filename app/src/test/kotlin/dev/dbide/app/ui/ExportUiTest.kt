package dev.dbide.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.dbide.app.EditorViewModel
import dev.dbide.app.ExportText
import dev.dbide.app.ExportViewModel
import dev.dbide.app.FakeConnectionService
import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.export.CsvExportReport
import dev.dbide.core.export.ExportRefusal
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.QueryResult
import dev.dbide.core.vault.VaultState
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * §2.10 through the real composables: the control, the sentence beside it, and the
 * cases where it is not offered at all.
 *
 * The assertion that matters most here is the dullest one — that the strip says
 * export runs the statement again. Everything else about an export can be recovered
 * from the file; a user who believed they were saving the grid in front of them
 * cannot recover the ten minutes during which the table changed underneath it.
 */
@OptIn(ExperimentalTestApi::class)
class ExportUiTest {
    @TempDir
    lateinit var directory: Path

    private val destination: Path get() = directory.resolve("Local.csv")

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        queryResult = QueryResult(
            columns = listOf(Column("total", "numeric", ColumnFormat.NUMBER)),
            rows = listOf(listOf(CellValue.Integer(42))),
            duration = 3.milliseconds,
        )
        exportContent = "total\r\n42\r\n"
        exportReport = CsvExportReport(rows = 1, bytes = 11, duration = 2.milliseconds)
    }

    /** Mounts the pane with a chooser that answers [chosen] without opening a window. */
    private fun ComposeUiTest.pane(
        service: FakeConnectionService,
        chosen: Path?,
        readOnly: Boolean = true,
    ): EditorViewModel {
        lateinit var model: EditorViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember {
                EditorViewModel(service, scope).also { it.show(connection(readOnly = readOnly)) }
            }
            val export = remember { ExportViewModel(service, scope) { chosen } }
            DbideTheme { QueryPane(model, export, onCopy = {}) }
        }
        waitForIdle()
        return model
    }

    private fun connection(readOnly: Boolean) = ConnectionConfig(
        id = ConnectionId("id-1"),
        name = "Local",
        engine = Engine.POSTGRES,
        host = "localhost",
        port = 5432,
        database = "dbide",
        username = "dbide",
        tlsMode = TlsMode.DISABLE,
        environment = Environment.DEV,
        readOnly = readOnly,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    private fun ComposeUiTest.run(sql: String) {
        onNodeWithContentDescription("editor-text").performTextInput(sql)
        onNodeWithContentDescription("editor-run").performClick()
        waitForIdle()
    }

    @Test
    fun `the strip says export runs the statement again`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            pane(service(), destination)
            run("select total from invoices")

            onNodeWithContentDescription("export-start").assertIsEnabled()
            onNodeWithContentDescription("export-status").assertTextEquals(ExportText.RERUN)
        }

    @Test
    fun `exporting writes the file and reports what reached it`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val service = service()
            pane(service, destination)
            run("select total from invoices")

            onNodeWithContentDescription("export-start").performClick()
            waitForIdle()

            assertEquals(listOf("select total from invoices" to destination), service.exports)
            assertEquals("total\r\n42\r\n", destination.readText())
            onNodeWithContentDescription("export-status")
                .assertTextEquals("Wrote 1 row to Local.csv.")
        }

    @Test
    fun `dismissing the save dialog leaves the strip where it was`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val service = service()
            pane(service, chosen = null)
            run("select total from invoices")

            onNodeWithContentDescription("export-start").performClick()
            waitForIdle()

            assertTrue(service.exports.isEmpty())
            onNodeWithContentDescription("export-status").assertTextEquals(ExportText.RERUN)
        }

    @Test
    fun `a running export can be stopped, and says so without turning red`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val service = service()
            pane(service, destination)
            run("select total from invoices")

            val gate = CompletableDeferred<Unit>()
            service.gate = gate
            onNodeWithContentDescription("export-start").performClick()
            waitForIdle()

            onNodeWithContentDescription("export-start").assertIsNotEnabled()
            onNodeWithContentDescription("export-status").assertTextEquals("Exporting to Local.csv…")

            onNodeWithContentDescription("export-stop").performClick()
            gate.complete(Unit)
            waitForIdle()

            onNodeWithContentDescription("export-status")
                .assertTextEquals("Export cancelled. No file was written.")
            assertTrue(!destination.exists(), "a cancelled export completed anyway")
        }

    @Test
    fun `a statement that modifies data is refused, with the reason beside the button`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            // Writable, so the statement runs; `RETURNING` is what gives it columns and
            // so a grid, which is the only way this refusal can be reached at all.
            pane(service(), destination, readOnly = false)
            run("insert into invoices (total) values (1) returning total")
            // The dialog the write confirmation raises has to be agreed to first.
            onNodeWithContentDescription("confirm-write").performClick()
            waitForIdle()

            onNodeWithContentDescription("export-start").assertIsNotEnabled()
            onNodeWithContentDescription("export-status")
                .assertTextEquals(ExportRefusal.MODIFIES_DATA.message)
        }

    @Test
    fun `a statement that returned no columns is not offered an export`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val service = service()
            service.queryResult = QueryResult(
                columns = emptyList(),
                rows = emptyList(),
                duration = 3.milliseconds,
                rowsAffected = 2,
            )
            pane(service, destination, readOnly = false)
            run("update invoices set total = total")
            onNodeWithContentDescription("confirm-write").performClick()
            waitForIdle()

            onNodeWithContentDescription("grid-command").assertIsDisplayed()
            onNodeWithContentDescription("export-start").assertDoesNotExist()
        }

    @Test
    fun `a new result clears the last export report`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            pane(service(), destination)
            run("select total from invoices")
            onNodeWithContentDescription("export-start").performClick()
            waitForIdle()
            onNodeWithContentDescription("export-status")
                .assertTextEquals("Wrote 1 row to Local.csv.")

            onNodeWithContentDescription("editor-run").performClick()
            waitForIdle()

            // A file written from the previous run is not a sentence about this one.
            onNodeWithContentDescription("export-status").assertTextEquals(ExportText.RERUN)
        }
}
