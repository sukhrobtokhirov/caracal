package dev.caracal.engine.postgres

import dev.caracal.core.result.CellValue as CoreCellValue
import dev.caracal.core.result.Column as CoreColumn
import dev.caracal.core.result.ColumnFormat as CoreColumnFormat
import dev.caracal.core.result.Truncation
import dev.caracal.engine.api.CellValue
import dev.caracal.engine.api.ColumnDescriptor
import dev.caracal.engine.api.ColumnFormat
import dev.caracal.engine.api.TemporalKind
import dev.caracal.engine.api.TruncationReason
import java.math.BigInteger

/**
 * Carries a PostgreSQL result across into the SPI's value model.
 *
 * This is the file the Phase 0 characterization tests were written to protect, so it
 * is worth naming what it must never do. `PostgresValues` reads `numeric`, `float4`
 * and `float8` as the text the server sent, because `getBigDecimal` throws on the
 * `NaN` that `numeric` is allowed to hold and a `Double` would round a `numeric(38,10)`
 * before anyone saw it. Nothing here undoes that: **no value passes through a
 * `Double`, and [CellValue.Floating] is never constructed.** The SPI has that arm for
 * engines whose float types arrive as floats; PostgreSQL's arrive as text and stay
 * exact, and a `NaN` that no `BigDecimal` can hold keeps the server's own spelling as
 * [CellValue.Text] rather than becoming a number that is not one.
 *
 * The one thing this mapping adds is routing by type name into the arms
 * `dev.caracal.core.result.CellValue` does not have — [CellValue.Json] and
 * [CellValue.Temporal]. Both hold the server's text verbatim, so the routing is a
 * label and not a conversion, and a value that was clipped is deliberately *not*
 * labelled: half a `jsonb` document is not JSON, and calling it JSON would hand a
 * pretty-printer something it cannot parse and a user a viewer that fails on the one
 * value they were inspecting closely.
 */
internal object PostgresCells {

    fun descriptor(column: CoreColumn): ColumnDescriptor = ColumnDescriptor(
        name = column.name,
        typeName = column.typeName,
        format = format(column.format),
    )

    fun value(value: CoreCellValue, column: CoreColumn): CellValue = when (value) {
        is CoreCellValue.Null -> CellValue.Null

        is CoreCellValue.Integer -> CellValue.Integer(BigInteger.valueOf(value.value))

        is CoreCellValue.Decimal -> CellValue.Decimal(value.value)

        is CoreCellValue.Bool -> CellValue.Bool(value.value)

        is CoreCellValue.Binary -> CellValue.Bytes(
            preview = value.preview,
            byteCount = value.byteCount.toLong(),
            truncated = value.truncated,
        )

        is CoreCellValue.Text -> text(value, column)
    }

    fun truncation(truncation: Truncation): TruncationReason? = when (truncation) {
        Truncation.NONE -> null
        Truncation.ROW_LIMIT -> TruncationReason.ROW_LIMIT
        Truncation.SIZE_LIMIT -> TruncationReason.SIZE_LIMIT
    }

    /**
     * Text, labelled by what the column says it is — unless it was clipped, in which
     * case it is a prefix and is only honestly described as one.
     */
    private fun text(value: CoreCellValue.Text, column: CoreColumn): CellValue {
        if (value.truncated) return CellValue.Text(value.value, truncated = true)
        val type = column.typeName.lowercase()
        return when {
            type == "json" || type == "jsonb" -> CellValue.Json(value.value)
            type in TEMPORAL -> CellValue.Temporal(TEMPORAL.getValue(type), value.value)
            else -> CellValue.Text(value.value)
        }
    }

    private fun format(format: CoreColumnFormat): ColumnFormat = when (format) {
        CoreColumnFormat.TEXT -> ColumnFormat.TEXT
        CoreColumnFormat.NUMBER -> ColumnFormat.NUMBER
        CoreColumnFormat.BOOLEAN -> ColumnFormat.BOOLEAN
        CoreColumnFormat.TEMPORAL -> ColumnFormat.TEMPORAL
        CoreColumnFormat.JSON -> ColumnFormat.JSON
        CoreColumnFormat.BINARY -> ColumnFormat.BINARY
    }

    /**
     * PostgreSQL's temporal types and what each one measures.
     *
     * `interval` is a span and not a point, which is why [TemporalKind] has an arm for
     * it rather than being a list of timestamp flavours.
     */
    private val TEMPORAL = mapOf(
        "date" to TemporalKind.DATE,
        "time" to TemporalKind.TIME,
        "timetz" to TemporalKind.TIME_WITH_ZONE,
        "timestamp" to TemporalKind.TIMESTAMP,
        "timestamptz" to TemporalKind.TIMESTAMP_WITH_ZONE,
        "interval" to TemporalKind.INTERVAL,
    )
}
