package dev.dbide.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateSet
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.QueryResult
import dev.dbide.core.result.Truncation
import java.util.Locale
import kotlin.time.Duration

/** One cell, addressed by position: two columns may share a name, so names cannot address one. */
data class CellPosition(val row: Int, val column: Int)

/** How a click on a row number combines with the rows already selected. */
enum class SelectionGesture { REPLACE, TOGGLE, EXTEND }

/**
 * Every string the grid shows or copies, in one place and with no Compose in it.
 *
 * Two rules run through all of it. A cell's *preview* is one line, bounded, and may
 * contain characters that stand in for what was removed; a cell's *copy* text is the
 * value exactly as [dev.dbide.core.result.CellValue] holds it, because a value that
 * changes on the way to the clipboard is a value the user cannot trust.
 */
object GridText {

    /** What a SQL NULL is called on screen and in copied text. */
    const val NULL: String = "NULL"

    /** What an empty string is drawn as, so that it is not an empty cell. */
    const val EMPTY: String = "\"\""

    /** How much of a value one line of the grid is allowed to show. */
    const val PREVIEW_CHARS: Int = 160

    private const val NEWLINE_GLYPH = '↵'
    private const val TAB_GLYPH = '→'
    private const val ELLIPSIS = '…'

    /**
     * The exact text of [value], for the clipboard and for anything downstream of it.
     *
     * NULL copies as the word `NULL`, which is what the grid shows and what §2.10
     * settles on for CSV. It is ambiguous against a string whose content is the four
     * letters N-U-L-L, and an empty field would be ambiguous against the empty string
     * instead; between two ambiguities, this is the one the user can see coming.
     */
    fun copy(value: CellValue): String = when (value) {
        CellValue.Null -> NULL
        is CellValue.Text -> value.value
        is CellValue.Integer -> value.value.toString()
        // toString, not toPlainString: the BigDecimal was parsed from PostgreSQL's own
        // text, and toString gives that text back, exponent and all.
        is CellValue.Decimal -> value.value.toString()
        is CellValue.Bool -> value.value.toString()
        is CellValue.Binary -> value.preview
    }

    /** One line of [value], at most [limit] characters, with placeholders for the empties. */
    fun preview(value: CellValue, limit: Int = PREVIEW_CHARS): String = when (value) {
        CellValue.Null -> NULL
        is CellValue.Text ->
            if (value.value.isEmpty()) EMPTY else mark(flatten(value.value, limit), value.truncated)

        is CellValue.Binary -> mark(flatten(value.preview, limit), value.truncated)
        else -> flatten(copy(value), limit)
    }

    /**
     * An ellipsis on a value the *server* cut short, not just one this line did.
     *
     * A `text` column holding a megabyte arrives already clipped by `ResultLimits`. If
     * the clipped prefix happens to fit the cell, nothing about the cell would say so,
     * and a short-looking value that is not short is the one kind of lie the grid must
     * not tell.
     */
    private fun mark(text: String, truncated: Boolean): String =
        if (truncated && !text.endsWith(ELLIPSIS)) text + ELLIPSIS else text

    /** Whether [value] is drawn as a placeholder rather than as its own text. */
    fun isPlaceholder(value: CellValue): Boolean =
        value == CellValue.Null || (value is CellValue.Text && value.value.isEmpty())

    /**
     * [text] on one line, cut to [limit].
     *
     * Newlines and tabs become visible glyphs rather than disappearing: a cell that
     * silently drops a line break looks like a shorter value than it is. The cut never
     * splits a surrogate pair, for the same reason `ResultLimits.clip` does not.
     */
    private fun flatten(text: String, limit: Int): String {
        val plain = text.length <= limit && text.none { it == '\n' || it == '\r' || it == '\t' }
        if (plain) return text

        val keep = when {
            text.length <= limit -> text.length
            text[limit - 1].isHighSurrogate() -> limit - 1
            else -> limit
        }
        val flat = StringBuilder(keep + 1)
        for (index in 0 until keep) {
            flat.append(
                when (val character = text[index]) {
                    '\n', '\r' -> NEWLINE_GLYPH
                    '\t' -> TAB_GLYPH
                    else -> character
                },
            )
        }
        if (keep < text.length) flat.append(ELLIPSIS)
        return flat.toString()
    }

    /**
     * [text] as one field of tab-separated text.
     *
     * A value holding a tab or a newline would otherwise become two fields or two
     * rows on paste. The quoting is the spreadsheet convention — wrap in double
     * quotes, double any double quote inside — which Excel, Numbers, and Sheets all
     * read back as the original single value.
     */
    fun escape(text: String): String =
        if (text.none { it == '\t' || it == '\n' || it == '\r' || it == '"' }) {
            text
        } else {
            "\"" + text.replace("\"", "\"\"") + "\""
        }

    /** One row of cells as a tab-separated line. */
    fun row(cells: List<CellValue>): String = cells.joinToString("\t") { field(it) }

    /**
     * One cell as one field of a row.
     *
     * A string whose content is the word `NULL` is quoted even when nothing else would
     * require it, so that the field beside a real NULL is visibly a different thing.
     * Copying a single cell on its own stays unquoted: there is no neighbouring NULL to
     * be confused with, and a pasted quote would be a character the value never had.
     */
    private fun field(value: CellValue): String {
        val text = copy(value)
        return if (value != CellValue.Null && text == NULL) "\"$text\"" else escape(text)
    }

    /** Several rows as tab-separated lines, in the order given. */
    fun rows(rows: List<List<CellValue>>): String = rows.joinToString("\n") { row(it) }

    /** What the status bar says about a finished statement. */
    fun status(result: QueryResult): String {
        val affected = result.rowsAffected
        val head = when {
            affected != null -> "${count(affected)} affected"
            result.rows.isEmpty() -> "No rows"
            else -> count(result.rows.size.toLong())
        }
        val truncation = when (result.truncation) {
            Truncation.NONE -> null
            Truncation.ROW_LIMIT -> "truncated at the row limit"
            Truncation.SIZE_LIMIT -> "truncated at the size limit"
        }
        return listOfNotNull(head, duration(result.duration), truncation).joinToString(" · ")
    }

    /** `1 row`, `1,204 rows` — grouped, so a six-figure count is readable at a glance. */
    fun count(rows: Long): String =
        String.format(Locale.ROOT, if (rows == 1L) "%,d row" else "%,d rows", rows)

    /** A duration at a precision a person can read, and compare between two runs. */
    fun duration(elapsed: Duration): String {
        val millis = elapsed.inWholeMicroseconds / 1000.0
        return when {
            millis < 10 -> String.format(Locale.ROOT, "%.2f ms", millis)
            millis < 1000 -> String.format(Locale.ROOT, "%.0f ms", millis)
            else -> String.format(Locale.ROOT, "%.2f s", millis / 1000)
        }
    }

    /**
     * How wide each column wants to be, in characters, measured from a sample.
     *
     * Measuring every row of a large result costs more than drawing it, and the rows
     * past the first screenful cannot influence a width the user will have resized by
     * the time they are reached. The header is always part of the measurement, so a
     * narrow column under a long name still shows its name.
     */
    fun sampleWidths(
        result: QueryResult,
        sampleRows: Int = 100,
        maxChars: Int = 48,
        minChars: Int = 6,
    ): List<Int> = result.columns.mapIndexed { index, column ->
        var widest = maxOf(column.name.length, column.typeName.length)
        for (row in result.rows.take(sampleRows)) {
            val cell = row.getOrNull(index) ?: continue
            widest = maxOf(widest, preview(cell, maxChars).length)
            if (widest >= maxChars) break
        }
        widest.coerceIn(minChars, maxChars)
    }
}

/**
 * The columns worth composing right now, and the empty space that stands in for the
 * rest.
 *
 * `LazyColumn` virtualizes rows and nothing else, so a two-hundred-column result
 * would compose two hundred cells per visible row and lay out all of them off-screen.
 * This is the horizontal half: given the column widths and where the viewport is, it
 * says which columns intersect it. The caller draws [leading] and [trailing] as plain
 * space, so the row is still its full width and the scrollbar still tells the truth.
 */
data class ColumnWindow(val first: Int, val last: Int, val leading: Float, val trailing: Float) {
    val range: IntRange get() = first..last
}

/**
 * The columns of [widths] visible in a [viewport]-wide window scrolled to [scroll],
 * plus [overscan] columns on each side so a scroll does not begin with a blank edge.
 */
fun columnWindow(
    widths: List<Float>,
    scroll: Float,
    viewport: Float,
    overscan: Int = 1,
): ColumnWindow {
    val total = widths.sum()
    if (widths.isEmpty()) return ColumnWindow(0, -1, 0f, 0f)

    val start = scroll.coerceAtLeast(0f)
    val end = start + viewport.coerceAtLeast(0f)
    var offset = 0f
    var first = -1
    var last = -1
    widths.forEachIndexed { index, width ->
        val right = offset + width
        if (right > start && offset < end) {
            if (first < 0) first = index
            last = index
        }
        offset = right
    }
    // No viewport yet, or a scroll position past the end of the content: nothing is
    // visible, and the row is one spacer of the full width.
    if (first < 0) return ColumnWindow(0, -1, total, 0f)

    first = (first - overscan).coerceAtLeast(0)
    last = (last + overscan).coerceAtMost(widths.lastIndex)
    var leading = 0f
    for (index in 0 until first) leading += widths[index]
    var trailing = 0f
    for (index in last + 1..widths.lastIndex) trailing += widths[index]
    return ColumnWindow(first, last, leading, trailing)
}

/**
 * Everything the grid remembers about one result: where it is scrolled, how wide its
 * columns are, which cell is focused, and which rows are selected.
 *
 * It lives outside the composables so that scrolling, selecting, and resizing survive
 * recomposition, and so that the parts worth asserting — what a copy produces, which
 * columns a scroll position makes visible — can be tested without a window.
 */
class ResultGridState(val result: QueryResult) {

    /** Shared by the header and every row, which is what keeps them aligned. */
    val horizontal: ScrollState = ScrollState(0)

    val vertical: LazyListState = LazyListState()

    /** Column widths in dp, sampled once and then owned by whoever drags a divider. */
    val widths: SnapshotStateList<Float> =
        mutableStateListOf<Float>().apply { repeat(result.columns.size) { add(DEFAULT_WIDTH) } }

    /** The cell the value panel describes and a bare copy would take. */
    var focused: CellPosition? by mutableStateOf(null)
        private set

    /** The rows a copy would take instead, when there are any. */
    val selectedRows: SnapshotStateSet<Int> = mutableStateSetOf()

    /** Whether the expanded-value panel is open. */
    var panelOpen: Boolean by mutableStateOf(false)
        private set

    /** Whether a JSON value in the panel is reformatted. The value itself never is. */
    var prettyJson: Boolean by mutableStateOf(true)
        private set

    /**
     * Whether the server's notices are showing. Open to begin with, whenever there
     * are any.
     *
     * A notice is the server volunteering something, and the statement that most needs
     * one read is the one that returns nothing else: a `DO` block that reports what it
     * checked has no rows, no count, and nothing on screen but its notices. Putting
     * them behind a control the user has to know about first is how they go unread, so
     * they are shown and then dismissible — the status bar keeps the count, so a panel
     * closed by mistake is one click from coming back.
     */
    var noticesOpen: Boolean by mutableStateOf(result.notices.isNotEmpty())
        private set

    private var anchor: Int? = null
    private var fitted = false

    /**
     * Sets the column widths from the sample, once, given the width of one character
     * in the grid's font.
     *
     * The sample is in characters because character counts are a property of the data;
     * turning them into dp needs a measured font, which only the composable has.
     */
    fun fitColumns(advance: Float, padding: Float = CELL_PADDING) {
        if (fitted) return
        fitted = true
        GridText.sampleWidths(result).forEachIndexed { index, characters ->
            widths[index] = (characters * advance + padding).coerceIn(MIN_WIDTH, MAX_WIDTH)
        }
    }

    /** Widens or narrows one column by a drag of [delta] dp. */
    fun resize(column: Int, delta: Float) {
        widths[column] = (widths[column] + delta).coerceIn(MIN_WIDTH, MAX_WIDTH)
    }

    /** Focuses one cell. A cell click is not a row selection: the two copy differently. */
    fun focus(row: Int, column: Int) {
        focused = CellPosition(row, column)
        selectedRows.clear()
        anchor = row
    }

    /** Selects a row from its row number, extending or toggling as the gesture says. */
    fun selectRow(row: Int, gesture: SelectionGesture) {
        when (gesture) {
            SelectionGesture.REPLACE -> {
                selectedRows.clear()
                selectedRows.add(row)
                anchor = row
            }

            SelectionGesture.TOGGLE -> {
                if (!selectedRows.remove(row)) selectedRows.add(row)
                anchor = row
            }

            SelectionGesture.EXTEND -> {
                val from = anchor ?: row
                selectedRows.clear()
                selectedRows.addAll(minOf(from, row)..maxOf(from, row))
            }
        }
        focused = CellPosition(row, focused?.column ?: 0)
    }

    fun openPanel() {
        panelOpen = true
    }

    fun closePanel() {
        panelOpen = false
    }

    fun togglePrettyJson() {
        prettyJson = !prettyJson
    }

    fun toggleNotices() {
        noticesOpen = !noticesOpen
    }

    /** What the notices control calls itself, or `null` when the server said nothing. */
    fun noticeLabel(): String? = when (result.notices.size) {
        0 -> null
        1 -> if (noticesOpen) "Hide notice" else "1 notice"
        else -> if (noticesOpen) "Hide notices" else "${result.notices.size} notices"
    }

    /** The focused cell's column, or `null` when nothing is focused. */
    fun focusedColumn(): Column? = focused?.let { result.columns.getOrNull(it.column) }

    /** The focused cell's value, or `null` when nothing is focused. */
    fun focusedValue(): CellValue? = focused?.let { result.rows.getOrNull(it.row)?.getOrNull(it.column) }

    /**
     * What a copy takes: the selected rows if any are selected, otherwise the focused
     * cell. One rule, so the keyboard shortcut and the button always agree.
     */
    fun copyText(): String? = when {
        selectedRows.isNotEmpty() ->
            GridText.rows(selectedRows.sorted().mapNotNull { result.rows.getOrNull(it) })

        else -> focusedValue()?.let { GridText.copy(it) }
    }

    /** What the copy control calls itself, so the user knows what is about to be taken. */
    fun copyLabel(): String = when {
        selectedRows.size > 1 -> "Copy ${selectedRows.size} rows"
        selectedRows.size == 1 -> "Copy row"
        focused != null -> "Copy cell"
        else -> "Copy"
    }

    companion object {
        const val MIN_WIDTH: Float = 48f
        const val MAX_WIDTH: Float = 520f
        const val DEFAULT_WIDTH: Float = 120f
        const val CELL_PADDING: Float = 18f
    }
}

/** Whether a column's values are right-aligned. Alignment is a column-wide property. */
val ColumnFormat.rightAligned: Boolean get() = this == ColumnFormat.NUMBER
