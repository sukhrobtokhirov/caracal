package dev.dbide.core.result

import java.math.BigDecimal
import kotlin.time.Duration

/** One column of a result set, as the grid needs to know it. */
data class Column(val name: String, val typeName: String)

/**
 * A cell, typed. The point of the sealed hierarchy is that `BigDecimal` and `Long`
 * reach the renderer intact instead of being flattened into strings on the way.
 */
sealed interface CellValue {
    data object Null : CellValue

    data class Text(val value: String) : CellValue

    data class Integer(val value: Long) : CellValue

    data class Decimal(val value: BigDecimal) : CellValue

    data class Bool(val value: Boolean) : CellValue

    /** A type M0 does not map yet. M2 replaces most of these with real cases. */
    data class Unmapped(val rendered: String) : CellValue
}

/**
 * A completed statement. [truncated] is true when the row limit cut the result
 * short.
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
    val truncated: Boolean = false,
    val rowsAffected: Long? = null,
)
