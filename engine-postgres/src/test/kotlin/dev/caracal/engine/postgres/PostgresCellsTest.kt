package dev.caracal.engine.postgres

import dev.caracal.core.result.CellValue as CoreCellValue
import dev.caracal.core.result.Column as CoreColumn
import dev.caracal.core.result.ColumnFormat as CoreColumnFormat
import dev.caracal.core.result.Truncation
import dev.caracal.engine.api.CellValue
import dev.caracal.engine.api.ColumnFormat
import dev.caracal.engine.api.TemporalKind
import dev.caracal.engine.api.TruncationReason
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The crossing from `:core`'s value model into the SPI's, tested for the one
 * property both of them exist to hold: a number that arrived exact leaves exact.
 *
 * `PostgresValuesTest` already proves the read side — that `numeric` comes off the
 * `ResultSet` through `getString` and not `getBigDecimal`, so that `NaN` survives.
 * This is the other half of the same guarantee, and it is the half a refactor
 * damages, because the SPI's model has a [CellValue.Floating] arm that the source
 * model does not and it is one careless `when` branch away from being used.
 */
class PostgresCellsTest {

    @Test
    fun `int8 at Long MAX_VALUE crosses without losing its last digit`() {
        val value = PostgresCells.value(CoreCellValue.Integer(Long.MAX_VALUE), column("int8"))

        assertEquals(CellValue.Integer(BigInteger.valueOf(Long.MAX_VALUE)), value)
        // The digits, spelled out, because 9223372036854775807 through a Double is
        // 9223372036854775808 and both of them look right at a glance.
        assertEquals("9223372036854775807", (value as CellValue.Integer).value.toString())
    }

    @Test
    fun `a numeric with more digits than a Double can hold keeps every one`() {
        val exact = BigDecimal("12345678901234567890.1234567890")

        val value = PostgresCells.value(CoreCellValue.Decimal(exact), column("numeric"))

        assertEquals(CellValue.Decimal(exact), value)
        assertEquals("12345678901234567890.1234567890", (value as CellValue.Decimal).value.toPlainString())
        // Scale is part of the value: numeric(38,10) that reads back as 20 digits
        // and no trailing zeroes is a different value to the one that was stored.
        assertEquals(10, value.value.scale())
    }

    @Test
    fun `NaN keeps the server's spelling rather than becoming a number that is not one`() {
        // `PostgresValues` cannot make a BigDecimal of it, so it arrives as text. The
        // temptation at this boundary is to route a NUMBER-formatted column into
        // Floating, where NaN would fit — and where 0.1 would stop being 0.1.
        val value = PostgresCells.value(CoreCellValue.Text("NaN"), column("numeric"))

        assertEquals(CellValue.Text("NaN"), value)
    }

    @Test
    fun `no PostgreSQL value is ever Floating`() {
        val everything = listOf(
            CoreCellValue.Null to column("text"),
            CoreCellValue.Text("hello") to column("text"),
            CoreCellValue.Text("NaN") to column("float8"),
            CoreCellValue.Text("Infinity") to column("float4"),
            CoreCellValue.Integer(42) to column("int4"),
            CoreCellValue.Decimal(BigDecimal("0.1")) to column("float8"),
            CoreCellValue.Decimal(BigDecimal("1.5")) to column("numeric"),
            CoreCellValue.Bool(true) to column("bool"),
            CoreCellValue.Binary("\\xff", 1, false) to column("bytea"),
        )

        everything.forEach { (source, column) ->
            val crossed = PostgresCells.value(source, column)
            assertTrue(
                crossed !is CellValue.Floating,
                "$source on ${column.typeName} became Floating, which rounds it",
            )
        }
    }

    @Test
    fun `a float8 read as text stays exact rather than becoming a Double`() {
        // 0.1 has no exact binary representation. Through Floating it would come back
        // as 0.1000000000000000055511151231257827, which is what the server did not say.
        val value = PostgresCells.value(CoreCellValue.Decimal(BigDecimal("0.1")), column("float8"))

        assertEquals(CellValue.Decimal(BigDecimal("0.1")), value)
    }

    @Test
    fun `json is labelled as json and holds the server's text byte for byte`() {
        val document = """{"a": 1, "b": [2, 3]}"""

        assertEquals(
            CellValue.Json(document),
            PostgresCells.value(CoreCellValue.Text(document), column("jsonb", CoreColumnFormat.JSON)),
        )
        assertEquals(
            CellValue.Json(document),
            PostgresCells.value(CoreCellValue.Text(document), column("json", CoreColumnFormat.JSON)),
        )
    }

    @Test
    fun `a clipped json value is text, because half a document is not json`() {
        val clipped = CoreCellValue.Text("""{"a": 1, "b": [2,""", truncated = true)

        val value = PostgresCells.value(clipped, column("jsonb", CoreColumnFormat.JSON))

        assertEquals(CellValue.Text("""{"a": 1, "b": [2,""", truncated = true), value)
    }

    @Test
    fun `a clipped text value says so on the other side`() {
        val value = PostgresCells.value(CoreCellValue.Text("abc", truncated = true), column("text"))

        assertEquals(CellValue.Text("abc", truncated = true), value)
    }

    @Test
    fun `every temporal type is labelled with what it measures`() {
        val expected = mapOf(
            "date" to TemporalKind.DATE,
            "time" to TemporalKind.TIME,
            "timetz" to TemporalKind.TIME_WITH_ZONE,
            "timestamp" to TemporalKind.TIMESTAMP,
            "timestamptz" to TemporalKind.TIMESTAMP_WITH_ZONE,
            "interval" to TemporalKind.INTERVAL,
        )

        expected.forEach { (typeName, kind) ->
            val value = PostgresCells.value(
                CoreCellValue.Text("2026-08-22 10:00:00+00"),
                column(typeName, CoreColumnFormat.TEMPORAL),
            )

            val temporal = assertIs<CellValue.Temporal>(value, typeName)
            assertEquals(kind, temporal.kind, typeName)
            assertEquals("2026-08-22 10:00:00+00", temporal.raw, typeName)
        }
    }

    @Test
    fun `infinity is a legal timestamp value and keeps the server's spelling`() {
        val value = PostgresCells.value(
            CoreCellValue.Text("infinity"),
            column("timestamptz", CoreColumnFormat.TEMPORAL),
        )

        val temporal = assertIs<CellValue.Temporal>(value)
        assertEquals("infinity", temporal.raw)
        // Nothing was parsed, and pretending otherwise would put a date on a value
        // that is not one.
        assertNull(temporal.parsed)
    }

    @Test
    fun `bytea keeps its true length alongside the preview it shows`() {
        val value = PostgresCells.value(
            CoreCellValue.Binary(preview = "\\xdeadbeef", byteCount = 40_000_000, truncated = true),
            column("bytea", CoreColumnFormat.BINARY),
        )

        assertEquals(CellValue.Bytes("\\xdeadbeef", 40_000_000L, truncated = true), value)
    }

    @Test
    fun `a type this build has never heard of arrives as the server's own text`() {
        val value = PostgresCells.value(CoreCellValue.Text("192.168.0.1/24"), column("inet"))

        assertEquals(CellValue.Text("192.168.0.1/24"), value)
    }

    @Test
    fun `null is null and not an empty string`() {
        assertEquals(CellValue.Null, PostgresCells.value(CoreCellValue.Null, column("text")))
    }

    @Test
    fun `every column format has a counterpart`() {
        val expected = mapOf(
            CoreColumnFormat.TEXT to ColumnFormat.TEXT,
            CoreColumnFormat.NUMBER to ColumnFormat.NUMBER,
            CoreColumnFormat.BOOLEAN to ColumnFormat.BOOLEAN,
            CoreColumnFormat.TEMPORAL to ColumnFormat.TEMPORAL,
            CoreColumnFormat.JSON to ColumnFormat.JSON,
            CoreColumnFormat.BINARY to ColumnFormat.BINARY,
        )

        // Over the enum's own entries, so a format added to one model and not the
        // other fails here rather than silently becoming TEXT.
        assertEquals(CoreColumnFormat.entries.toSet(), expected.keys)
        expected.forEach { (source, target) ->
            assertEquals(target, PostgresCells.descriptor(column("x", source)).format)
        }
    }

    @Test
    fun `a column keeps the label it was headed with and PostgreSQL's own type name`() {
        val descriptor = PostgresCells.descriptor(CoreColumn(name = "amount", typeName = "numeric"))

        assertEquals("amount", descriptor.name)
        assertEquals("numeric", descriptor.typeName)
    }

    @Test
    fun `truncation crosses, and no truncation crosses as nothing`() {
        assertNull(PostgresCells.truncation(Truncation.NONE))
        assertEquals(TruncationReason.ROW_LIMIT, PostgresCells.truncation(Truncation.ROW_LIMIT))
        assertEquals(TruncationReason.SIZE_LIMIT, PostgresCells.truncation(Truncation.SIZE_LIMIT))
    }

    private fun column(typeName: String, format: CoreColumnFormat = CoreColumnFormat.TEXT) =
        CoreColumn(name = "value", typeName = typeName, format = format)
}
