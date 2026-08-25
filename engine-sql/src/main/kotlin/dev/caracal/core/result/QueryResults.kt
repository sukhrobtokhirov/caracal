package dev.caracal.core.result

import dev.caracal.engine.api.CellValue as EngineCellValue
import dev.caracal.engine.api.ColumnDescriptor
import dev.caracal.engine.api.ColumnFormat as EngineColumnFormat
import dev.caracal.engine.api.EngineError
import dev.caracal.engine.api.Row
import dev.caracal.engine.api.StatementExecution
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.TruncationReason
import java.math.BigInteger
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow

/**
 * The stream of outcomes an engine produces, read back as the one completed result
 * the grid draws.
 *
 * This file is the seam issue #4 removed the last bypass of. `QueryFacet` streams —
 * a descriptor, then rows as they arrive, then whatever the run left to say — and
 * `:core` returns a whole [QueryResult], because a grid draws a page and a page is a
 * finished thing. Somebody has to bridge those, and the choice was between an engine
 * that materializes for one caller and a caller that collects for itself. The second
 * is what is here, and it is the honest one: the streaming shape is what a
 * cursor-held fetch-more and a progressive grid need later, and an engine that
 * quietly assembled a list first would have made both impossible while looking
 * finished.
 *
 * It lives in `:engine-sql` rather than in `:core` because both halves are visible
 * here and because every SQL engine's caller needs exactly this: §12 lists the result
 * grid model among the things that are shared correctly, and this is the conversion
 * into it.
 */

/**
 * Runs to the end of [StatementExecution.outcomes] and returns what happened.
 *
 * Collecting is what runs the statement, so this is where it runs. The row flow is
 * consumed inside the emission that carried it, which is the contract
 * [StatementOutcome.Rows] states: the engine is holding a cursor open behind it and
 * may close it the moment this returns.
 *
 * Nothing is bounded here. The engine was told what to read — the row count through
 * [dev.caracal.engine.api.StatementRequest.maxRows], the size of a value through
 * [dev.caracal.engine.api.ValueDetail] — and has already applied it, so what arrives
 * is a page that is within budget. A second budget applied on top could only disagree
 * with the first, and the disagreement would show as a result the engine says was
 * complete and this says was not.
 *
 * A [StatementOutcome.Failed] throws rather than returning, because that is what
 * every caller of this already expects: `ConnectionService.execute` reports a failure
 * by throwing, the editor catches it, and a statement that failed has no result to
 * hand back. See [EngineError.asDbException] for what is thrown.
 */
suspend fun StatementExecution.materialize(): QueryResult {
    var columns: List<Column> = emptyList()
    var rows: List<List<CellValue>> = emptyList()
    var rowsAffected: Long? = null
    var truncation = Truncation.NONE
    val notices = mutableListOf<Notice>()
    // Only used when nothing carried an elapsed of its own. See below.
    val started = TimeSource.Monotonic.markNow()
    var elapsed: Duration? = null

    outcomes.collect { outcome ->
        when (outcome) {
            is StatementOutcome.Rows -> {
                columns = outcome.descriptor.columns.map(ColumnDescriptor::toColumn)
                // Inside the emission, deliberately. Keeping the flow to collect after
                // this block returns finds a result set the engine has closed.
                rows = outcome.rows.toRows()
                elapsed = outcome.descriptor.elapsed ?: elapsed
            }

            is StatementOutcome.UpdateCount -> rowsAffected = outcome.count

            is StatementOutcome.Truncated -> truncation = outcome.reason.toTruncation()

            is StatementOutcome.Notice -> notices += Notice(
                message = outcome.text,
                severity = outcome.severity,
                sqlState = outcome.sqlState,
                detail = outcome.detail,
                hint = outcome.hint,
            )

            is StatementOutcome.Failed -> throw outcome.error.asDbException()
        }
    }

    return QueryResult(
        columns = columns,
        rows = rows,
        // The descriptor's own measurement where there is one, because it is the
        // server's time and not this loop's. An engine that sends no descriptor —
        // an `INSERT`, a `SET`, a `DO` block whose whole output was a notice — has
        // nothing to report but how long the round trip took, and that is measured
        // here rather than left at zero.
        duration = elapsed ?: started.elapsedNow(),
        truncation = truncation,
        rowsAffected = rowsAffected,
        notices = notices,
    )
}

/**
 * What to throw for a statement the server rejected.
 *
 * The engine's own classified failure travels as [EngineError.cause] and is preferred
 * when it is there, which for every SQL engine in this repository it is. That is not
 * a shortcut past the SPI, it is the SPI's `cause` field doing the job it is declared
 * for, and it is what keeps the error model whole: [EngineError] is the portable
 * shape and [DbError] is the richer one this application renders, carrying the
 * severity, the object the server named, and the position *as the server counted it*.
 * Rebuilding a [DbError] from an [EngineError] instead would drop the first two and
 * would have to invert the position mapping that had just been applied to it — an
 * inversion whose only purpose is to undo a conversion nobody asked for, and which is
 * exactly how an underline lands one character off.
 *
 * The fallback is for an engine that classifies nothing and throws nothing: its
 * failure is reported with the message, the code and the position it did send, and
 * without the two fields it never had.
 */
fun EngineError.asDbException(): DbException {
    (cause as? DbException)?.let { return it }
    return DbException(
        DbError.QueryFailed(
            message = message,
            sqlState = code,
            // Deliberately not [EngineError.position]. That is an offset into the
            // editor buffer, already mapped; `DbError.QueryFailed.position` is the
            // server's own 1-based count into the statement it received, and putting
            // one in the other's field is a wrong underline rather than a missing one.
            position = null,
            detail = detail,
            hint = hint,
        ),
        cause,
    )
}

/**
 * One column of a streamed result, as the grid's own model.
 *
 * A rename and nothing else: `:core`'s [Column] and the SPI's [ColumnDescriptor]
 * carry the same three facts, because the SPI's was made from it.
 */
fun ColumnDescriptor.toColumn(): Column = Column(
    name = name,
    typeName = typeName,
    format = when (format) {
        EngineColumnFormat.TEXT -> ColumnFormat.TEXT
        EngineColumnFormat.NUMBER -> ColumnFormat.NUMBER
        EngineColumnFormat.BOOLEAN -> ColumnFormat.BOOLEAN
        EngineColumnFormat.TEMPORAL -> ColumnFormat.TEMPORAL
        EngineColumnFormat.JSON -> ColumnFormat.JSON
        EngineColumnFormat.BINARY -> ColumnFormat.BINARY
    },
)

/**
 * A streamed cell, as the grid's own model.
 *
 * The SPI's value model is the wider of the two, on purpose — it has to hold a
 * `BIGINT UNSIGNED` that does not fit a `Long` and an array that arrives as elements
 * rather than as text — so this direction is where a value can be lost, and every arm
 * below is written so that it is not.
 *
 * The rule is that nothing is ever *converted*: an arm that does not exist in
 * [CellValue] becomes [CellValue.Text] holding the server's own spelling, byte for
 * byte, which is what the grid renders for it anyway. An integer too large for a
 * `Long` is the one case where the narrower model genuinely cannot hold the value,
 * and it takes the same route rather than wrapping round — a `BIGINT UNSIGNED` past
 * 2^63 shows as the digits the server sent and not as a negative number.
 */
fun EngineCellValue.toCore(): CellValue = when (this) {
    EngineCellValue.Null -> CellValue.Null

    is EngineCellValue.Text -> CellValue.Text(value, truncated)

    is EngineCellValue.Integer ->
        if (value in LONG_RANGE) CellValue.Integer(value.toLong()) else CellValue.Text(value.toString())

    is EngineCellValue.Decimal -> CellValue.Decimal(value)

    // Reserved for the types that were never exact, so its own `toString` is the
    // whole of what it has. PostgreSQL never constructs one — `float8` arrives as the
    // text the server sent — and an engine whose floats really are floats gets the
    // shortest decimal that round trips, which is what `Double.toString` produces.
    is EngineCellValue.Floating -> CellValue.Text(value.toString())

    is EngineCellValue.Bool -> CellValue.Bool(value)

    // The count is a `Long` in the SPI and an `Int` here, and a preview of a value
    // larger than two gigabytes is still a preview: the number is only ever shown as
    // "of n bytes", so it is clamped rather than allowed to wrap into a negative size.
    is EngineCellValue.Bytes -> CellValue.Binary(
        preview = preview,
        byteCount = byteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        truncated = truncated,
    )

    // Both hold the server's text verbatim and are labelled by the column's format,
    // which is how the grid decides to offer a viewer. Nothing is reformatted.
    is EngineCellValue.Json -> CellValue.Text(raw)

    is EngineCellValue.Temporal -> CellValue.Text(raw)

    is EngineCellValue.Opaque -> CellValue.Text(display)

    /*
     * The one arm with no verbatim text to fall back on, because an array arrived as
     * elements rather than as the literal the server wrote. It is rendered in the
     * `{a,b}` form, which is PostgreSQL's own and the only array syntax this model has
     * ever carried — and the reason an engine whose arrays are spelled differently
     * should send [EngineCellValue.Text] holding the server's own rendering instead.
     * PostgreSQL does exactly that, so this arm is unreachable for it.
     */
    is EngineCellValue.Array -> CellValue.Text(
        elements.joinToString(separator = ",", prefix = "{", postfix = "}") { element ->
            when (val core = element.toCore()) {
                CellValue.Null -> "NULL"
                is CellValue.Text -> core.value
                is CellValue.Integer -> core.value.toString()
                is CellValue.Decimal -> core.value.toPlainString()
                is CellValue.Bool -> if (core.value) "t" else "f"
                is CellValue.Binary -> core.preview
            }
        },
    )
}

/** A streamed row, as the grid's own model. */
fun List<EngineCellValue>.toCore(): List<CellValue> = map { it.toCore() }

private fun TruncationReason.toTruncation(): Truncation = when (this) {
    TruncationReason.ROW_LIMIT -> Truncation.ROW_LIMIT
    TruncationReason.SIZE_LIMIT -> Truncation.SIZE_LIMIT
}

private suspend fun Flow<Row>.toRows(): List<List<CellValue>> {
    val collected = ArrayList<List<CellValue>>()
    collect { row -> collected += row.cells.toCore() }
    return collected
}

private val LONG_RANGE = BigInteger.valueOf(Long.MIN_VALUE)..BigInteger.valueOf(Long.MAX_VALUE)
