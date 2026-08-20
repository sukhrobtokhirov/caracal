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

/** A completed read. [truncated] is true when [rowLimit] cut the result short. */
data class QueryResult(
    val columns: List<Column>,
    val rows: List<List<CellValue>>,
    val duration: Duration,
    val truncated: Boolean = false,
)
