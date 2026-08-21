package dev.dbide.app.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.dbide.app.ResultGridState
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.Notice
import dev.dbide.core.result.QueryResult
import dev.dbide.core.result.ResultLimits
import dev.dbide.core.result.Truncation
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The grid, driven through the real composables.
 *
 * The assertions that matter here are the ones a view-model test cannot make: that a
 * result far larger than the screen composes only what the screen shows, that the
 * header a column is under is the header it scrolled with, and that the row numbers
 * stay put while the columns move.
 */
@OptIn(ExperimentalTestApi::class)
class ResultGridUiTest {

    private val copied = mutableListOf<String>()

    private fun rows(count: Int, columns: Int): List<List<CellValue>> =
        List(count) { row -> List(columns) { column -> CellValue.Text("r${row}c$column") } }

    private fun wide(rowCount: Int = 5_000, columnCount: Int = 40) = QueryResult(
        columns = List(columnCount) { Column("column_$it", "text") },
        rows = rows(rowCount, columnCount),
        duration = 12.milliseconds,
    )

    /** Mounts the grid on its own and hands back the state the test can drive. */
    private fun ComposeUiTest.grid(result: QueryResult): ResultGridState {
        lateinit var state: ResultGridState
        setContent {
            state = remember { ResultGridState(result) }
            DbideTheme { ResultGrid(state, onCopy = { copied += it }) }
        }
        waitForIdle()
        return state
    }

    private fun ComposeUiTest.scrollRight(state: ResultGridState, to: Int) {
        runBlocking { state.horizontal.scrollTo(to) }
        waitForIdle()
    }

    // --- What a cell shows ---------------------------------------------------

    @Test
    fun `the header carries the column name and PostgreSQL's own type name`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = listOf(Column("total", "int8", ColumnFormat.NUMBER)),
                    rows = listOf(listOf(CellValue.Integer(42))),
                    duration = 3.milliseconds,
                ),
            )

            onNodeWithContentDescription("grid-header-0").assertTextContains("total")
            onNodeWithContentDescription("grid-header-0").assertTextContains("int8")
            onNodeWithContentDescription("grid-cell-0-0").assertTextEquals("42")
        }

    @Test
    fun `a null, an empty string, and the word NULL are three different cells`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = listOf(Column("a", "text"), Column("b", "text"), Column("c", "text")),
                    rows = listOf(listOf(CellValue.Null, CellValue.Text(""), CellValue.Text("NULL"))),
                    duration = 3.milliseconds,
                ),
            )

            onNodeWithContentDescription("grid-cell-0-0").assertTextEquals("NULL")
            onNodeWithContentDescription("grid-cell-0-1").assertTextEquals("\"\"")
            onNodeWithContentDescription("grid-cell-0-2").assertTextEquals("NULL")
        }

    // --- Virtualization ------------------------------------------------------

    @Test
    fun `five thousand rows compose a screenful`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val state = grid(wide())

            onNodeWithContentDescription("grid-cell-0-0").assertIsDisplayed()
            onNodeWithContentDescription("grid-cell-4000-0").assertDoesNotExist()

            runBlocking { state.vertical.scrollToItem(4_000) }
            waitForIdle()

            onNodeWithContentDescription("grid-cell-4000-0").assertIsDisplayed()
            onNodeWithContentDescription("grid-cell-0-0").assertDoesNotExist()
        }

    @Test
    fun `a hundred thousand rows by fifty columns is a screenful of work`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            // The scale plan §5.9 asks to be measured at. The rows share one list, so
            // this is a test of what the grid composes and not of what a result costs
            // to hold; a grid that lays out every row or every column hangs here.
            val row = List(50) { CellValue.Text("value $it") }
            val state = grid(
                QueryResult(
                    columns = List(50) { Column("column_$it", "text") },
                    rows = List(100_000) { row },
                    duration = 900.milliseconds,
                ),
            )

            onNodeWithContentDescription("grid-cell-0-0").assertIsDisplayed()
            onNodeWithContentDescription("grid-cell-99999-0").assertDoesNotExist()

            runBlocking { state.vertical.scrollToItem(99_999) }
            waitForIdle()
            scrollRight(state, 100_000)

            onNodeWithContentDescription("grid-cell-99999-49").assertIsDisplayed()
            onNodeWithContentDescription("grid-status")
                .assertTextEquals("100,000 rows · 900 ms")
        }

    @Test
    fun `forty columns compose a screenful, and the header composes the same ones`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val state = grid(wide())

            onNodeWithContentDescription("grid-cell-0-0").assertIsDisplayed()
            onNodeWithContentDescription("grid-cell-0-39").assertDoesNotExist()
            onNodeWithContentDescription("grid-header-39").assertDoesNotExist()

            // Far enough right that the first columns are behind the left edge.
            scrollRight(state, 10_000)

            onNodeWithContentDescription("grid-cell-0-39").assertIsDisplayed()
            // One scroll state drives the header and every row, so the column that
            // arrived on screen arrived under its own name.
            onNodeWithContentDescription("grid-header-39").assertIsDisplayed()
            onNodeWithContentDescription("grid-cell-0-0").assertDoesNotExist()
        }

    @Test
    fun `the row numbers stay where they are when the columns scroll`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val state = grid(wide())
            onNodeWithContentDescription("grid-row-0").assertIsDisplayed()

            scrollRight(state, 10_000)

            onNodeWithContentDescription("grid-row-0").assertIsDisplayed()
        }

    // --- Selection and copying -----------------------------------------------

    @Test
    fun `clicking a cell and pressing copy takes that cell exactly`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = listOf(Column("note", "text"), Column("n", "int8", ColumnFormat.NUMBER)),
                    rows = listOf(
                        listOf(CellValue.Text("first\tline"), CellValue.Integer(1)),
                        listOf(CellValue.Text("second"), CellValue.Integer(2)),
                    ),
                    duration = 3.milliseconds,
                ),
            )

            onNodeWithContentDescription("grid-cell-0-0").performClick()
            waitForIdle()
            onNodeWithContentDescription("grid-copy").assertTextEquals("Copy cell")
            onNodeWithContentDescription("grid-copy").performClick()
            waitForIdle()

            // The value, tab included and unescaped: one cell is not a row of fields.
            assertEquals(listOf("first\tline"), copied)
        }

    @Test
    fun `clicking a row number takes the whole row, escaped so it pastes as one row`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = listOf(Column("note", "text"), Column("n", "int8", ColumnFormat.NUMBER)),
                    rows = listOf(
                        listOf(CellValue.Text("first\tline"), CellValue.Integer(1)),
                        listOf(CellValue.Null, CellValue.Integer(2)),
                    ),
                    duration = 3.milliseconds,
                ),
            )

            onNodeWithContentDescription("grid-row-1").performClick()
            waitForIdle()
            onNodeWithContentDescription("grid-copy").assertTextEquals("Copy row")
            onNodeWithContentDescription("grid-copy").performClick()
            waitForIdle()

            assertEquals(listOf("NULL\t2"), copied)
        }

    @Test
    fun `the copy chord copies the focused cell`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = listOf(Column("note", "text")),
                    rows = listOf(listOf(CellValue.Text("copied by keyboard"))),
                    duration = 3.milliseconds,
                ),
            )
            // The click is what gives the grid the keyboard, deliberately: a result
            // arriving must not take focus away from the editor that produced it.
            onNodeWithContentDescription("grid-cell-0-0").performClick()
            waitForIdle()

            onNodeWithContentDescription("result-grid").performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.C) }
            }
            waitForIdle()

            assertEquals(listOf("copied by keyboard"), copied)
        }

    // --- The value panel -----------------------------------------------------

    @Test
    fun `double-clicking a clipped value opens the whole of what was kept`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val long = ResultLimits(cellCharacters = 300).clip("x".repeat(1_000))
            grid(
                QueryResult(
                    columns = listOf(Column("body", "text")),
                    rows = listOf(listOf(long)),
                    duration = 3.milliseconds,
                ),
            )

            onNodeWithContentDescription("grid-detail").assertDoesNotExist()
            onNodeWithContentDescription("grid-cell-0-0").performMouseInput { doubleClick() }
            waitForIdle()

            onNodeWithContentDescription("grid-detail-value").assertTextEquals("x".repeat(300))
            // And it says that this is not all of it.
            onNodeWithContentDescription("grid-detail-truncated").assertIsDisplayed()

            onNodeWithContentDescription("grid-detail-close").performClick()
            waitForIdle()
            onNodeWithContentDescription("grid-detail").assertDoesNotExist()
        }

    @Test
    fun `a JSON value reads pretty and copies raw`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val raw = """{"id":1,"tags":["a"]}"""
            grid(
                QueryResult(
                    columns = listOf(Column("doc", "jsonb", ColumnFormat.JSON)),
                    rows = listOf(listOf(CellValue.Text(raw))),
                    duration = 3.milliseconds,
                ),
            )

            onNodeWithContentDescription("grid-cell-0-0").performMouseInput { doubleClick() }
            waitForIdle()

            onNodeWithContentDescription("grid-detail-value")
                .assertTextEquals("{\n  \"id\": 1,\n  \"tags\": [\n    \"a\"\n  ]\n}")

            // The pretty view is a view: what leaves the application is what arrived.
            onNodeWithContentDescription("grid-detail-copy").performClick()
            waitForIdle()
            assertEquals(listOf(raw), copied)

            onNodeWithContentDescription("grid-detail-json").performClick()
            waitForIdle()
            onNodeWithContentDescription("grid-detail-value").assertTextEquals(raw)
        }

    // --- The status bar ------------------------------------------------------

    @Test
    fun `the status bar reports the count, the time, and the truncation`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = listOf(Column("n", "int8", ColumnFormat.NUMBER)),
                    rows = List(1_000) { listOf(CellValue.Integer(it.toLong())) },
                    duration = 812.milliseconds,
                    truncation = Truncation.ROW_LIMIT,
                ),
            )

            onNodeWithContentDescription("grid-status")
                .assertTextEquals("1,000 rows · 812 ms · truncated at the row limit")
        }

    @Test
    fun `a statement that returned no columns says so instead of drawing an empty table`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = emptyList(),
                    rows = emptyList(),
                    duration = 4.milliseconds,
                    rowsAffected = 3,
                ),
            )

            onNodeWithContentDescription("grid-command").assertIsDisplayed()
            onNodeWithContentDescription("grid-status").assertTextEquals("3 rows affected · 4.00 ms")
            onNodeWithContentDescription("grid-header-0").assertDoesNotExist()
        }

    // --- Notices --------------------------------------------------------------

    @Test
    fun `a statement whose only output was a notice shows the notice`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            // The failure this rules out is the whole reason notices are carried: a
            // DO block that reports what it found returns no columns and no count, so
            // dropping its notices leaves "Statement completed." and nothing else.
            grid(
                QueryResult(
                    columns = emptyList(),
                    rows = emptyList(),
                    duration = 4.milliseconds,
                    notices = listOf(Notice("checked 3 tables", severity = "NOTICE")),
                ),
            )

            onNodeWithContentDescription("grid-notices").assertIsDisplayed()
            onNodeWithContentDescription("grid-notice-0").assertTextContains("NOTICE: checked 3 tables")
        }

    @Test
    fun `a notice shows its severity, and its detail and hint under it`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = listOf(Column("id", "int8", ColumnFormat.NUMBER)),
                    rows = listOf(listOf(CellValue.Integer(1))),
                    duration = 4.milliseconds,
                    notices = listOf(
                        Notice(
                            message = "nothing was dropped",
                            severity = "WARNING",
                            detail = "The table was not there.",
                            hint = "Check the schema.",
                        ),
                    ),
                ),
            )

            val notice = onNodeWithContentDescription("grid-notice-0")
            notice.assertTextContains("WARNING: nothing was dropped")
            notice.assertTextContains("Detail: The table was not there.")
            notice.assertTextContains("Hint: Check the schema.")
        }

    @Test
    fun `notices open by themselves, close on request, and come back from the status bar`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            // Open to begin with because a notice nobody knows to look for is a notice
            // nobody reads; dismissible because the second look does not need it.
            grid(
                QueryResult(
                    columns = listOf(Column("id", "int8", ColumnFormat.NUMBER)),
                    rows = listOf(listOf(CellValue.Integer(1))),
                    duration = 4.milliseconds,
                    notices = listOf(Notice("first"), Notice("second")),
                ),
            )

            onNodeWithContentDescription("grid-notices").assertIsDisplayed()
            onNodeWithContentDescription("grid-notices-close").performClick()

            onNodeWithContentDescription("grid-notices").assertDoesNotExist()
            onNodeWithContentDescription("grid-toggle-notices").assertTextEquals("2 notices")

            onNodeWithContentDescription("grid-toggle-notices").performClick()
            onNodeWithContentDescription("grid-notice-1").assertTextContains("second")
        }

    @Test
    fun `a result the server said nothing about has no notices control at all`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = listOf(Column("id", "int8", ColumnFormat.NUMBER)),
                    rows = listOf(listOf(CellValue.Integer(1))),
                    duration = 4.milliseconds,
                ),
            )

            onNodeWithContentDescription("grid-notices").assertDoesNotExist()
            onNodeWithContentDescription("grid-toggle-notices").assertDoesNotExist()
        }

    @Test
    fun `a notice is never drawn as anything but text`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            // Server-authored text on the same terms as a cell value. There is no
            // renderer here that could interpret it, and this is what keeps it that way.
            val hostile = "<b>bold</b> & ${'$'}{injected}"
            grid(
                QueryResult(
                    columns = emptyList(),
                    rows = emptyList(),
                    duration = 4.milliseconds,
                    notices = listOf(Notice(hostile)),
                ),
            )

            onNodeWithContentDescription("grid-notice-0").assertTextContains(hostile)
        }

    @Test
    fun `a query that matched nothing shows its columns and says there were no rows`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            grid(
                QueryResult(
                    columns = listOf(Column("id", "int8", ColumnFormat.NUMBER)),
                    rows = emptyList(),
                    duration = 4.milliseconds,
                ),
            )

            onNodeWithContentDescription("grid-header-0").assertIsDisplayed()
            onNodeWithContentDescription("grid-empty").assertIsDisplayed()
            assertTrue(copied.isEmpty())
        }
}
