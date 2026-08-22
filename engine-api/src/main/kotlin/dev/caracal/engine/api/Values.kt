package dev.caracal.engine.api

import java.math.BigDecimal
import java.math.BigInteger

/**
 * A cell, typed, in the one shape every engine must produce.
 *
 * The hierarchy exists to make a lossy value impossible to express rather than
 * merely discouraged. `:core`'s own [dev.caracal.core.result.CellValue] holds an
 * `int8` in a `Long`, which is exactly right for PostgreSQL and wrong for the next
 * engine along: MySQL's `BIGINT UNSIGNED` runs to 2^64 - 1 and does not fit. So the
 * integer arm here is a [BigInteger] and the decimal arm a [BigDecimal], and no arm
 * anywhere accepts a value that has already been through a `Double`.
 *
 * [Floating] is the one arm that can lose digits, and it is reserved for the types
 * that were never exact to begin with — `float4`, `float8`, `REAL`. An engine that
 * routes `numeric` or `DECIMAL` through it has broken the guarantee this file is
 * for. PostgreSQL's driver, as it happens, never constructs one: it reads all three
 * of its number types as the text the server sent, because `getBigDecimal` throws on
 * the `NaN` that `numeric` is allowed to hold. A value that will not fit a
 * [BigDecimal] therefore arrives as [Text] carrying the server's own spelling, which
 * is the honest answer and not a fallback.
 *
 * [Opaque] is the escape hatch and it is deliberately not a hole: `display` is
 * whatever the server wrote, verbatim, so a type this build has never heard of shows
 * correctly on the first day rather than after a release.
 */
sealed interface CellValue {
    data object Null : CellValue

    /** [truncated] means the value shown is a prefix; the rest stayed on the server. */
    data class Text(val value: String, val truncated: Boolean = false) : CellValue

    /** Every exact integer type, up to and including one that does not fit a `Long`. */
    data class Integer(val value: BigInteger) : CellValue

    /** Every exact decimal type. Scale is part of the value and is preserved. */
    data class Decimal(val value: BigDecimal) : CellValue

    /** Approximate types only — `float4`, `float8`, `REAL`. Never an exact one. */
    data class Floating(val value: Double) : CellValue

    data class Bool(val value: Boolean) : CellValue

    /**
     * Binary data as a bounded, engine-rendered preview.
     *
     * §6.1 of the spec asks for the bytes themselves. The repository wins: PostgreSQL
     * renders `bytea` in its own `\x` hexadecimal and keeps [byteCount] alongside, so
     * the grid can say how much of a forty-megabyte value it is not showing. Handing
     * over a `ByteArray` instead would drop that count and would retain the whole
     * value to display a kilobyte of it.
     */
    data class Bytes(val preview: String, val byteCount: Long, val truncated: Boolean) : CellValue

    /** JSON, exactly as the server sent it. Pretty-printing is the viewer's business. */
    data class Json(val raw: String) : CellValue

    /**
     * A point or span in time. [raw] is the server's rendering and is what round
     * trips; [parsed] is a convenience for the engines that could parse it, and null
     * for the values that are legal and are not points in time — PostgreSQL's
     * `infinity` among them.
     */
    data class Temporal(val kind: TemporalKind, val raw: String, val parsed: Any? = null) : CellValue

    data class Array(val elements: List<CellValue>, val elementType: String) : CellValue

    /**
     * A type we can show but not interpret. Never lossy: [display] is verbatim.
     */
    data class Opaque(val typeName: String, val display: String) : CellValue
}

/** What a [CellValue.Temporal] measures. The distinction the renderer needs. */
enum class TemporalKind { DATE, TIME, TIME_WITH_ZONE, TIMESTAMP, TIMESTAMP_WITH_ZONE, INTERVAL }

/**
 * How the grid should present a column: alignment, and which viewer is offered.
 *
 * A column-wide decision, made by the engine, because only the engine knows that
 * `oid` is a number and `inet` is not. The same six cases as `:core`'s own
 * `ColumnFormat`, which this will replace once the grid reads the SPI.
 */
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
