package dev.dbide.app

import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.QueryResult
import dev.dbide.core.result.ResultLimits
import dev.dbide.core.result.Truncation
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

/**
 * What the grid says and what it copies, with no window involved.
 *
 * The rules worth pinning down here are the ones a screenshot cannot check: that a
 * copied value is byte-for-byte what the server sent, that a tab inside a value does
 * not become a second column on paste, and that a wide result composes only the
 * columns the viewport can actually show.
 */
class ResultGridStateTest {

    private fun result(
        columns: List<Column>,
        rows: List<List<CellValue>>,
        truncation: Truncation = Truncation.NONE,
        rowsAffected: Long? = null,
    ) = QueryResult(columns, rows, 5.milliseconds, truncation, rowsAffected)

    private val text = Column("note", "text")
    private val count = Column("total", "int8", ColumnFormat.NUMBER)

    // --- What a cell shows ---------------------------------------------------

    @Test
    fun `a null and an empty string are two different things on screen`() {
        assertEquals("NULL", GridText.preview(CellValue.Null))
        assertEquals("\"\"", GridText.preview(CellValue.Text("")))
        assertTrue(GridText.isPlaceholder(CellValue.Null))
        assertTrue(GridText.isPlaceholder(CellValue.Text("")))
        // A value whose own text is the word NULL is not a placeholder.
        assertFalse(GridText.isPlaceholder(CellValue.Text("NULL")))
    }

    @Test
    fun `a preview is one line, and says where the line breaks were`() {
        assertEquals("a↵b→c", GridText.preview(CellValue.Text("a\nb\tc")))
        assertEquals("a↵↵b", GridText.preview(CellValue.Text("a\r\nb")))
    }

    @Test
    fun `a preview is bounded, and never cut through a character`() {
        val preview = GridText.preview(CellValue.Text("x".repeat(500)), limit = 10)
        assertEquals("xxxxxxxxxx…", preview)

        // A pile of astral-plane characters: cutting at an odd UTF-16 index would
        // leave half of one behind, which renders as a replacement glyph.
        val emoji = GridText.preview(CellValue.Text("🐘".repeat(20)), limit = 5)
        assertEquals("🐘🐘…", emoji)
    }

    @Test
    fun `a value the server cut short says so in the cell`() {
        val clipped = ResultLimits(cellCharacters = 8).clip("x".repeat(40))
        assertTrue(clipped.truncated)
        assertTrue(GridText.preview(clipped).endsWith("…"))
    }

    // --- What a cell copies --------------------------------------------------

    @Test
    fun `a copy is the value, not the preview`() {
        assertEquals("a\nb", GridText.copy(CellValue.Text("a\nb")))
        assertEquals("", GridText.copy(CellValue.Text("")))
        assertEquals("NULL", GridText.copy(CellValue.Null))
        assertEquals("9223372036854775807", GridText.copy(CellValue.Integer(Long.MAX_VALUE)))
        assertEquals("true", GridText.copy(CellValue.Bool(true)))
        assertEquals("\\x00ff", GridText.copy(CellValue.Binary("\\x00ff", 2, truncated = false)))
    }

    @Test
    fun `a numeric keeps the scale and the notation PostgreSQL sent`() {
        assertEquals("1.2300", GridText.copy(CellValue.Decimal(BigDecimal("1.2300"))))
        assertEquals("1E+10", GridText.copy(CellValue.Decimal(BigDecimal("1E+10"))))
    }

    @Test
    fun `a tab or a newline inside a value does not become another field or row`() {
        assertEquals("plain", GridText.escape("plain"))
        assertEquals("\"a\tb\"", GridText.escape("a\tb"))
        assertEquals("\"a\nb\"", GridText.escape("a\nb"))
        assertEquals("\"say \"\"hi\"\"\"", GridText.escape("say \"hi\""))
    }

    @Test
    fun `rows copy as tab-separated lines`() {
        val rows = listOf(
            listOf(CellValue.Text("a\tb"), CellValue.Integer(1)),
            listOf(CellValue.Null, CellValue.Integer(2)),
        )
        assertEquals("\"a\tb\"\t1\nNULL\t2", GridText.rows(rows))
    }

    @Test
    fun `a row tells a null apart from the string that spells it`() {
        val rows = listOf(listOf(CellValue.Null, CellValue.Text("NULL")))
        assertEquals("NULL\t\"NULL\"", GridText.rows(rows))
        // On its own, a cell copies as itself: there is no NULL beside it to be
        // confused with, and a quote would be a character the value never had.
        assertEquals("NULL", GridText.copy(CellValue.Text("NULL")))
    }

    // --- The status bar ------------------------------------------------------

    @Test
    fun `the status bar counts rows, times them, and admits truncation`() {
        val rows = List(1_000) { listOf(CellValue.Integer(it.toLong())) }
        assertEquals(
            "1,000 rows · 5.00 ms · truncated at the row limit",
            GridText.status(result(listOf(count), rows, Truncation.ROW_LIMIT)),
        )
        assertEquals("1 row · 5.00 ms", GridText.status(result(listOf(count), rows.take(1))))
        assertEquals("No rows · 5.00 ms", GridText.status(result(listOf(count), emptyList())))
        assertEquals(
            "3 rows affected · 5.00 ms",
            GridText.status(result(emptyList(), emptyList(), rowsAffected = 3)),
        )
    }

    @Test
    fun `a duration is readable at every scale a query takes`() {
        assertEquals("0.42 ms", GridText.duration(420.microseconds))
        assertEquals("64 ms", GridText.duration(64.milliseconds))
        assertEquals("2.50 s", GridText.duration(2_500.milliseconds))
    }

    // --- Column widths -------------------------------------------------------

    @Test
    fun `a column is as wide as its header when its values are narrower`() {
        val widths = GridText.sampleWidths(
            result(listOf(Column("a_very_long_column_name", "text")), listOf(listOf(CellValue.Text("x")))),
        )
        assertEquals("a_very_long_column_name".length, widths.single())
    }

    @Test
    fun `one enormous value does not make a column enormous`() {
        val widths = GridText.sampleWidths(
            result(listOf(text), listOf(listOf(CellValue.Text("x".repeat(9_000))))),
            maxChars = 48,
        )
        assertEquals(48, widths.single())
    }

    @Test
    fun `widths come from a sample, not from every row`() {
        val rows = List(5_000) { index ->
            listOf(CellValue.Text(if (index == 4_999) "a hundred characters wide".repeat(4) else "ab"))
        }
        // The wide value is past the sample, so it does not set the width. It is why
        // sampling is stated rather than implied: the last row can be wider than the
        // column, and the value panel is how it is read.
        assertEquals(6, GridText.sampleWidths(result(listOf(text), rows), sampleRows = 10).single())
    }

    @Test
    fun `fitting turns sampled characters into dp once, and a drag takes it from there`() {
        val state = ResultGridState(result(listOf(text), listOf(listOf(CellValue.Text("abcdefghij")))))
        state.fitColumns(advance = 7f, padding = 10f)
        assertEquals(10 * 7f + 10f, state.widths[0])

        // A second fit would undo a resize the user has already made.
        state.resize(0, 40f)
        state.fitColumns(advance = 7f, padding = 10f)
        assertEquals(10 * 7f + 50f, state.widths[0])

        state.resize(0, -10_000f)
        assertEquals(ResultGridState.MIN_WIDTH, state.widths[0])
        state.resize(0, 10_000f)
        assertEquals(ResultGridState.MAX_WIDTH, state.widths[0])
    }

    // --- Horizontal virtualization -------------------------------------------

    @Test
    fun `only the columns the viewport touches are in the window`() {
        val widths = List(50) { 100f }

        val start = columnWindow(widths, scroll = 0f, viewport = 300f, overscan = 0)
        assertEquals(0..2, start.range)
        assertEquals(0f, start.leading)
        assertEquals(4_700f, start.trailing)

        val scrolled = columnWindow(widths, scroll = 1_000f, viewport = 300f, overscan = 0)
        assertEquals(10..12, scrolled.range)
        assertEquals(1_000f, scrolled.leading)
        assertEquals(3_700f, scrolled.trailing)
        // The spacers plus the visible columns are still the full width, so the
        // scrollbar describes the whole result rather than the part on screen.
        assertEquals(5_000f, scrolled.leading + 300f + scrolled.trailing)
    }

    @Test
    fun `the window has a column of slack on each side`() {
        val window = columnWindow(List(50) { 100f }, scroll = 1_000f, viewport = 300f, overscan = 1)
        assertEquals(9..13, window.range)
    }

    @Test
    fun `a window over nothing, or before layout, asks for nothing`() {
        assertTrue(columnWindow(emptyList(), scroll = 0f, viewport = 500f).range.isEmpty())

        val unlaidOut = columnWindow(List(4) { 100f }, scroll = 0f, viewport = 0f)
        assertTrue(unlaidOut.range.isEmpty())
        assertEquals(400f, unlaidOut.leading)
    }

    // --- Selection -----------------------------------------------------------

    private fun grid(rows: Int = 5) = ResultGridState(
        result(listOf(text, count), List(rows) { listOf(CellValue.Text("r$it"), CellValue.Integer(it.toLong())) }),
    )

    @Test
    fun `a copy takes the focused cell when no row is selected`() {
        val state = grid()
        assertNull(state.copyText())
        assertEquals("Copy", state.copyLabel())

        state.focus(row = 2, column = 0)
        assertEquals("r2", state.copyText())
        assertEquals("Copy cell", state.copyLabel())
    }

    @Test
    fun `a copy takes whole rows once rows are selected`() {
        val state = grid()
        state.selectRow(1, SelectionGesture.REPLACE)
        assertEquals("r1\t1", state.copyText())
        assertEquals("Copy row", state.copyLabel())

        state.selectRow(3, SelectionGesture.EXTEND)
        assertEquals("r1\t1\nr2\t2\nr3\t3", state.copyText())
        assertEquals("Copy 3 rows", state.copyLabel())

        state.selectRow(2, SelectionGesture.TOGGLE)
        assertEquals("r1\t1\nr3\t3", state.copyText())

        // Focusing a cell is a different question, and it clears the answer to the
        // other one rather than copying both.
        state.focus(row = 0, column = 1)
        assertEquals("0", state.copyText())
    }

    @Test
    fun `an extend with nothing behind it selects the one row`() {
        val state = grid()
        state.selectRow(2, SelectionGesture.EXTEND)
        assertEquals(setOf(2), state.selectedRows.toSet())
    }

    @Test
    fun `the value panel describes the focused cell`() {
        val state = grid()
        assertNull(state.focusedValue())

        state.focus(row = 4, column = 1)
        assertEquals(CellValue.Integer(4), state.focusedValue())
        assertEquals("total", state.focusedColumn()?.name)
        assertFalse(state.panelOpen)
        state.openPanel()
        assertTrue(state.panelOpen)
    }
}
