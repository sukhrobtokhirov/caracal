package dev.dbide.core.result

import java.math.BigDecimal
import kotlin.time.Duration

/** How the grid should present a column. Alignment and viewers are column-wide. */
enum class ColumnFormat {
    TEXT,

    /** Right-aligned. Includes values a number cannot hold, like `NaN`. */
    NUMBER,
    BOOLEAN,
    TEMPORAL,

    /** A pretty-printed viewer is available; the value itself stays exactly as sent. */
    JSON,
    BINARY,
}

/**
 * One column of a result set.
 *
 * [typeName] is PostgreSQL's own name for the type — `int8`, `timestamptz`,
 * `_int4` — and not the JDBC approximation of it. It is what the encoder keys on
 * and what the grid header shows.
 *
 * There is no type OID here. pgjdbc does not expose one through its public API
 * (`PGResultSetMetaData` offers base column, table, schema, and wire format, and
 * nothing else), and the name carries the same information in a form a person can
 * read in a header.
 *
 * Names are not unique. `SELECT 1 AS a, 2 AS a` is valid, so a cell is addressed
 * by position and never by column name.
 */
data class Column(
    val name: String,
    val typeName: String,
    val format: ColumnFormat = ColumnFormat.TEXT,
)

/**
 * A cell, typed.
 *
 * The point of the sealed hierarchy is that `BigDecimal` and `Long` reach the
 * renderer intact instead of being flattened into strings on the way. The source
 * plan required `int8` and `numeric` to travel as strings, because they crossed a
 * JSON wire into JavaScript, where an `int8` past 2^53 quietly loses its last
 * digits. There is no wire and no JavaScript any more, so the rule that existed to
 * protect those values is exactly the rule that would now damage them.
 *
 * Everything PostgreSQL renders unambiguously as text and the grid has no reason
 * to interpret — uuid, inet, arrays, ranges, intervals, enums, composites, and
 * types this build has never heard of — arrives as [Text] holding the server's own
 * text form, byte for byte.
 */
sealed interface CellValue {
    data object Null : CellValue

    /** [truncated] means the value shown is a prefix; the full one stayed on the server. */
    data class Text(val value: String, val truncated: Boolean = false) : CellValue

    data class Integer(val value: Long) : CellValue

    data class Decimal(val value: BigDecimal) : CellValue

    data class Bool(val value: Boolean) : CellValue

    /**
     * `bytea`, as a bounded hexadecimal preview in PostgreSQL's own `\x` form.
     * [byteCount] is the true length, so the grid can say how much is not shown.
     */
    data class Binary(val preview: String, val byteCount: Int, val truncated: Boolean) : CellValue
}

/** Why a result stopped short of everything the query would have returned. */
enum class Truncation {
    NONE,

    /** More rows matched than the row limit allows. */
    ROW_LIMIT,

    /** The rows were small in number and large in bytes. */
    SIZE_LIMIT,
}

/**
 * What a result is allowed to cost.
 *
 * A thousand rows is not a bound. One `jsonb` document or one `bytea` of a few
 * hundred megabytes is a thousand rows' worth of memory in a single cell, so a
 * limit on rows alone leaves the window one `SELECT *` away from dying.
 *
 * These bound what the application *retains and renders*. The driver has already
 * buffered the row it handed over, so this is not a claim about what crossed the
 * network — it is what keeps a result from being held, laid out, and drawn.
 */
data class ResultLimits(
    val rows: Int = 1_000,
    val cellCharacters: Int = 4_096,
    val binaryPreviewBytes: Int = 1_024,
    val totalCharacters: Int = 16 * 1024 * 1024,
) {
    /**
     * [text] cut to [cellCharacters], never through the middle of a character.
     *
     * Kotlin counts UTF-16 units, and a character outside the BMP is two of them.
     * Cutting between the two leaves an unpaired surrogate: not a character, not
     * valid UTF-8 once encoded, and rendered as a replacement glyph in the one cell
     * a user is most likely to be inspecting closely.
     */
    fun clip(text: String): CellValue.Text {
        if (text.length <= cellCharacters) return CellValue.Text(text)
        val end = if (text[cellCharacters - 1].isHighSurrogate()) cellCharacters - 1 else cellCharacters
        return CellValue.Text(text.substring(0, end), truncated = true)
    }
}

/**
 * A completed statement.
 *
 * [rowsAffected] is set only for a statement that returned no rows, and is the
 * closest thing to PostgreSQL's command tag that JDBC exposes: pgjdbc surfaces the
 * count but not the tag itself, and inventing `"UPDATE 3"` from the count and the
 * first keyword would be reporting a server value that was never received.
 */
data class QueryResult(
    val columns: List<Column>,
    val rows: List<List<CellValue>>,
    val duration: Duration,
    val truncation: Truncation = Truncation.NONE,
    val rowsAffected: Long? = null,
) {
    /** Whether anything was left behind. [truncation] says what did it. */
    val truncated: Boolean get() = truncation != Truncation.NONE
}
