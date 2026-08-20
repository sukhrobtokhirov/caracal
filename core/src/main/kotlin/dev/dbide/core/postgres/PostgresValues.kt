package dev.dbide.core.postgres

import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.ResultLimits
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Turns a JDBC column into a [Column] and a JDBC cell into a [CellValue].
 *
 * Driven by PostgreSQL's type name rather than by `getObject`, which decides for
 * itself what a value should become and is wrong in ways that are hard to see: a
 * `numeric` becomes a `BigDecimal` until the day it holds `NaN` and throws, and a
 * `timestamptz` becomes a `Timestamp` rendered in whatever time zone the JVM
 * happens to be in.
 *
 * The default is deliberately to keep PostgreSQL's own text. The server can render
 * every one of its types unambiguously, including the ones this build has never
 * heard of, so a type that gets no special handling here still arrives exactly as
 * the server wrote it — which is better than a guess, and is also what makes an
 * extension type work on the first day rather than after a release.
 */
internal object PostgresValues {

    fun column(metaData: ResultSetMetaData, index: Int): Column {
        val typeName = metaData.getColumnTypeName(index)
        return Column(
            // The label, not the name: `SELECT total AS amount` is headed `amount`.
            name = metaData.getColumnLabel(index),
            typeName = typeName,
            format = formatOf(typeName),
        )
    }

    fun read(rows: ResultSet, index: Int, column: Column, limits: ResultLimits): CellValue {
        val value = when (column.typeName.lowercase()) {
            in INTEGER_TYPES -> CellValue.Integer(rows.getLong(index))
            in NUMBER_TYPES -> rows.number(index)
            "bool" -> CellValue.Bool(rows.getBoolean(index))
            "bytea" -> rows.binary(index, limits)
            "timestamptz" -> rows.timestampAtUtc(index, limits)
            else -> rows.text(index, limits)
        }
        // getLong and getBoolean answer 0 and false for SQL NULL, so the question has
        // to be asked after the read rather than before it.
        return if (rows.wasNull()) CellValue.Null else value
    }

    /**
     * `numeric`, `float4`, and `float8`, read as the text the server sent.
     *
     * `getBigDecimal` would be the obvious call and it throws on `NaN`, which
     * PostgreSQL accepts in both `numeric` and the float types, along with
     * `Infinity` and `-Infinity`. Parsing the server's own text instead handles all
     * four without a special case per type, and preserves the exact scale of a
     * `numeric(20,10)` rather than whatever a `Double` would round it to.
     */
    private fun ResultSet.number(index: Int): CellValue {
        val text = getString(index) ?: return CellValue.Null
        return runCatching { CellValue.Decimal(BigDecimal(text)) }
            .getOrElse { CellValue.Text(text) }
    }

    /**
     * `timestamptz` normalized to UTC.
     *
     * The one type whose text form is not a fact about the value. PostgreSQL
     * renders it in the session's `TimeZone`, which pgjdbc sets from the JVM
     * default, so the same row reads differently on two machines and differently
     * again after a daylight-saving change. Reading it as an `OffsetDateTime` and
     * writing it back at UTC makes the column mean one thing everywhere. A local
     * rendering is a display choice the grid can offer later, from this.
     *
     * `infinity` and `-infinity` are legal values that are not points in time; they
     * keep the server's spelling.
     */
    private fun ResultSet.timestampAtUtc(index: Int, limits: ResultLimits): CellValue {
        val text = getString(index) ?: return CellValue.Null
        if (text in ENDLESS) return CellValue.Text(text)
        val moment = runCatching { getObject(index, OffsetDateTime::class.java) }.getOrNull()
            ?: return limits.clip(text)
        return CellValue.Text(TIMESTAMP_UTC.format(moment.withOffsetSameInstant(ZoneOffset.UTC)))
    }

    /**
     * `bytea` as PostgreSQL's own `\x` hexadecimal, bounded.
     *
     * [ResultLimits.binaryPreviewBytes] bounds what is kept and drawn, not what was
     * transferred: pgjdbc has already decoded the whole value into the row it handed
     * over. Rendering all of a 40 MB image as hexadecimal is what would actually
     * hurt, and nobody reads it.
     */
    private fun ResultSet.binary(index: Int, limits: ResultLimits): CellValue {
        val bytes = getBytes(index) ?: return CellValue.Null
        val shown = minOf(bytes.size, limits.binaryPreviewBytes)
        val hex = StringBuilder(2 + shown * 2).append("\\x")
        for (position in 0 until shown) hex.append(HEX[bytes[position].toInt() and 0xff])
        return CellValue.Binary(
            preview = hex.toString(),
            byteCount = bytes.size,
            truncated = shown < bytes.size,
        )
    }

    private fun ResultSet.text(index: Int, limits: ResultLimits): CellValue {
        val text = getString(index) ?: return CellValue.Null
        return limits.clip(text)
    }

    private fun formatOf(typeName: String): ColumnFormat = when (typeName.lowercase()) {
        in INTEGER_TYPES, in NUMBER_TYPES -> ColumnFormat.NUMBER
        "bool" -> ColumnFormat.BOOLEAN
        "bytea" -> ColumnFormat.BINARY
        "json", "jsonb" -> ColumnFormat.JSON
        in TEMPORAL_TYPES -> ColumnFormat.TEMPORAL
        else -> ColumnFormat.TEXT
    }

    /** `oid` is unsigned 32-bit, so it needs the 64-bit read like the rest. */
    private val INTEGER_TYPES = setOf("int2", "int4", "int8", "oid")

    private val NUMBER_TYPES = setOf("numeric", "float4", "float8")

    private val TEMPORAL_TYPES = setOf("date", "time", "timetz", "timestamp", "timestamptz", "interval")

    private val ENDLESS = setOf("infinity", "-infinity")

    /**
     * ISO-8601 with an explicit offset and no `T`, which is how PostgreSQL writes a
     * timestamp. The fraction is appended rather than written as an optional pattern
     * section, because an optional section still prints `.000000` for a whole second
     * — the field has a value, it is just zero.
     */
    private val TIMESTAMP_UTC: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd HH:mm:ss")
        .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
        .appendPattern("xxx")
        .toFormatter()

    private val HEX = Array(256) { "%02x".format(it) }
}
