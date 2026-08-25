package dev.caracal.engine.sqlite

import dev.caracal.core.result.ResultLimits
import dev.caracal.engine.api.CellValue
import dev.caracal.engine.api.ColumnDescriptor
import dev.caracal.engine.api.ColumnFormat
import dev.caracal.engine.api.TemporalKind
import java.math.BigInteger
import java.sql.ResultSet

/**
 * Turns a SQLite column into a [ColumnDescriptor] and a SQLite cell into a
 * [CellValue].
 *
 * **The rule this file exists to hold: the value decides its own type, and the column
 * only decides how to label it.** That is not a stylistic preference, it is what
 * SQLite is. A column declared `INTEGER` may hold the string `n/a`; SQLite calls the
 * declared type an *affinity*, applies it as a preference when a value is written, and
 * stores whatever survived. Every other engine this application speaks to answers
 * "what type is this column" once per result set. Here that question has no answer,
 * and a mapping that assumed it did would render `n/a` as a number or, worse, as a
 * zero.
 *
 * So the arm is chosen from the *storage class* — SQLite's own per-value type, which
 * `getObject` reports faithfully: an `Integer` or `Long` for `SQLITE_INTEGER`, a
 * `Double` for `SQLITE_FLOAT`, a `ByteArray` for `SQLITE_BLOB`, a `String` for
 * `SQLITE_TEXT`, and null for `SQLITE_NULL`. The declared type is read once, up front,
 * for two narrower jobs: the header a person reads, and the alignment and viewer the
 * grid offers. Neither can turn a string into a number.
 *
 * The one place the declared type touches a value is a text one, and only to *label*
 * it — a `JSON` column's string becomes [CellValue.Json] and a `DATE` column's becomes
 * [CellValue.Temporal], both holding the same characters SQLite stored. That is the
 * same thing `PostgresCells` does and it is safe for the same reason: the label is not
 * a conversion, and a value that was clipped is deliberately not labelled, because half
 * a JSON document is not JSON.
 *
 * [CellValue.Floating] is constructed here, and it is the arm PostgreSQL never uses.
 * That is the honest reading of `SQLITE_FLOAT`: it is an IEEE double in the file, it
 * was never exact, and rendering it as a [CellValue.Decimal] would claim a precision
 * the bytes do not have. It is also why this engine declares
 * [dev.caracal.engine.api.EngineCapabilities.exactNumerics] false.
 */
internal object SqliteValues {

    /**
     * The result set's columns, read once before the first row.
     *
     * Bounded by nothing and wrapped in `runCatching` for one reason: sqlite-jdbc
     * computes some of this metadata from the *current row*, so a result set with no
     * rows can refuse to describe itself. A statement that returned no rows still has
     * to draw a header, and a header of the right width with a blank type name is
     * better than an exception on an empty table.
     */
    fun columns(rows: ResultSet): List<ColumnDescriptor> {
        val meta = rows.metaData
        return (1..meta.columnCount).map { index ->
            // Trimmed because the driver derives this by cutting a declared type at
            // its first bracket, so `VARCHAR (20)` arrives with the space still on it.
            val typeName = runCatching { meta.getColumnTypeName(index) }.getOrNull().orEmpty().trim()
            ColumnDescriptor(
                // The label, not the name: `SELECT total AS amount` is headed `amount`.
                name = runCatching { meta.getColumnLabel(index) }.getOrNull() ?: "column$index",
                typeName = typeName,
                format = formatOf(typeName),
            )
        }
    }

    /**
     * One cell, typed by what SQLite actually stored in it.
     *
     * `getObject` rather than a read chosen from the column's type, which is the whole
     * argument of this file. It also means `wasNull` never has to be asked: the null
     * arrives as a null instead of as the zero that `getLong` would invent.
     */
    fun read(rows: ResultSet, index: Int, column: ColumnDescriptor, limits: ResultLimits): CellValue =
        when (val value = rows.getObject(index)) {
            null -> CellValue.Null

            // sqlite-jdbc narrows to an Int when the value fits one, so both arms are
            // the same storage class and both are exact.
            is Int -> CellValue.Integer(BigInteger.valueOf(value.toLong()))
            is Long -> CellValue.Integer(BigInteger.valueOf(value))

            is Double -> CellValue.Floating(value)

            is ByteArray -> binary(value, limits)

            is String -> text(value, column, limits)

            // Not reachable through the five storage classes, and here because "not
            // reachable" is a claim about a driver version. Verbatim, so a value this
            // build has never heard of shows correctly rather than not at all.
            else -> CellValue.Opaque(column.typeName, value.toString())
        }

    /**
     * A blob as bounded hexadecimal, in PostgreSQL's `\x` spelling.
     *
     * The spelling is shared on purpose: it is the one the grid already renders and
     * the one an export already writes, and inventing a second would make a binary
     * column look like a different kind of thing depending on which engine it came
     * from. [ResultLimits.binaryPreviewBytes] bounds what is *kept and drawn*, not what
     * was read — the driver has already handed over the whole array — and the byte
     * count travels with it so the grid can say how much it is not showing.
     */
    private fun binary(bytes: ByteArray, limits: ResultLimits): CellValue {
        val shown = minOf(bytes.size, limits.binaryPreviewBytes)
        // Computed as a Long and clamped: an export sets the bound to Int.MAX_VALUE,
        // and past about a gigabyte `2 + shown * 2` overflows to a negative Int.
        val capacity = (2L + shown.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val hex = StringBuilder(capacity).append("\\x")
        for (position in 0 until shown) hex.append(HEX[bytes[position].toInt() and 0xff])
        return CellValue.Bytes(
            preview = hex.toString(),
            byteCount = bytes.size.toLong(),
            truncated = shown < bytes.size,
        )
    }

    /**
     * Text, labelled by what the column says it is — unless it was clipped, in which
     * case it is a prefix and is only honestly described as one.
     */
    private fun text(value: String, column: ColumnDescriptor, limits: ResultLimits): CellValue {
        val clipped = limits.clip(value)
        if (clipped.truncated) return CellValue.Text(clipped.value, truncated = true)
        val declared = column.typeName.uppercase()
        return when {
            declared == "JSON" || declared == "JSONB" -> CellValue.Json(value)
            declared in TEMPORAL -> CellValue.Temporal(TEMPORAL.getValue(declared), value)
            else -> CellValue.Text(value)
        }
    }

    /**
     * How the grid should present a column, from the declared type's *affinity*.
     *
     * Affinity rather than an exact name, because SQLite accepts any type name at all
     * — `VARCHAR(255)`, `UNSIGNED BIG INT`, `wibble` — and decides what it means by
     * looking for five substrings in a fixed order. Reimplementing that order is the
     * only way to be right about a schema somebody actually wrote, and it is five
     * lines. Getting it wrong means `BIGINT` right-aligns and `UNSIGNED BIG INT` does
     * not, in the same table.
     *
     * This is a column-wide *presentation* choice and never a claim about a value. A
     * cell in a NUMBER column that holds text still arrives as [CellValue.Text].
     */
    private fun formatOf(typeName: String): ColumnFormat {
        val declared = typeName.uppercase()
        // A column the driver would not describe, which is not the same as a column
        // declared with no type. The second has BLOB affinity by SQLite's rules; this
        // is an unanswered question, and left-aligned text is the reading that shows
        // every value correctly rather than the one that is right about the schema.
        if (declared.isBlank()) return ColumnFormat.TEXT
        return when {
            declared == "JSON" || declared == "JSONB" -> ColumnFormat.JSON
            declared == "BOOLEAN" || declared == "BOOL" -> ColumnFormat.BOOLEAN
            declared in TEMPORAL -> ColumnFormat.TEMPORAL
            // SQLite's own order, from §3.1 of "Datatypes In SQLite", and the order is
            // the point: `INT` is tested before `CHAR`, so a column declared
            // `INTCHAR` is a number, and `BLOB` before `REAL`. Reimplementing this is
            // the only way to be right about a schema somebody actually wrote.
            declared.contains("INT") -> ColumnFormat.NUMBER
            declared.contains("CHAR") || declared.contains("CLOB") || declared.contains("TEXT") ->
                ColumnFormat.TEXT
            declared.contains("BLOB") -> ColumnFormat.BINARY
            declared.contains("REAL") || declared.contains("FLOA") || declared.contains("DOUB") ->
                ColumnFormat.NUMBER
            // NUMERIC affinity, which is everything else — including `DECIMAL(30,10)`,
            // the declared type this engine cannot keep the digits of.
            else -> ColumnFormat.NUMBER
        }
    }

    /**
     * The type names SQLite's own documentation suggests for dates and times, and what
     * each one measures.
     *
     * SQLite has no temporal type; these are declared types over text, integers and
     * reals, and the labelling here only ever applies to the text ones. A `DATETIME`
     * column holding a Unix epoch integer arrives as an integer, which is what it is.
     */
    private val TEMPORAL = mapOf(
        "DATE" to TemporalKind.DATE,
        "TIME" to TemporalKind.TIME,
        "DATETIME" to TemporalKind.TIMESTAMP,
        "TIMESTAMP" to TemporalKind.TIMESTAMP,
    )

    private val HEX = Array(256) { "%02x".format(it) }
}
