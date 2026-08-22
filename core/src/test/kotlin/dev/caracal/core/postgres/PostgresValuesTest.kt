package dev.caracal.core.postgres

import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.ColumnFormat
import dev.caracal.core.result.ResultLimits
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Characterization of the type mapping, ahead of the multi-engine refactor.
 *
 * `PostgresValues` had no test that runs without a server: its only exercise was
 * `PostgresTypesIntegrationTest`, which is gated behind `CARACAL_INTEGRATION` and so
 * does not run on an ordinary `check`. The precision guarantee this file makes —
 * `numeric` and `int8` arrive whole — is the one the value model in the multi-engine
 * spec is built around, and a refactor that quietly routed a value through a `Double`
 * would have had nothing standing in its way.
 *
 * The result set here is a proxy rather than a mock library, because what is being
 * pinned down is precisely *which getter* is called for each type. A fake that
 * answers every getter would let a change from `getString` to `getBigDecimal` pass —
 * and that change is exactly the bug: `getBigDecimal` throws on the `NaN` that
 * PostgreSQL accepts in a `numeric`.
 */
class PostgresValuesTest {

    private val limits = ResultLimits()

    // ---------------------------------------------------------------- columns

    @Test
    fun `a column takes the label rather than the name, so an alias is what the header shows`() {
        val column = PostgresValues.column(metaData("amount" to "numeric"), 1)
        assertEquals("amount", column.name)
        assertEquals("numeric", column.typeName)
    }

    @ParameterizedTest(name = "{0} is presented as {1}")
    @CsvSource(
        "int2, NUMBER",
        "int4, NUMBER",
        "int8, NUMBER",
        "oid, NUMBER",
        "numeric, NUMBER",
        "float4, NUMBER",
        "float8, NUMBER",
        "bool, BOOLEAN",
        "bytea, BINARY",
        "json, JSON",
        "jsonb, JSON",
        "date, TEMPORAL",
        "time, TEMPORAL",
        "timetz, TEMPORAL",
        "timestamp, TEMPORAL",
        "timestamptz, TEMPORAL",
        "interval, TEMPORAL",
        "text, TEXT",
        "uuid, TEXT",
        "inet, TEXT",
        "_int4, TEXT",
    )
    fun `the type name decides how the grid presents the column`(typeName: String, format: ColumnFormat) {
        assertEquals(format, PostgresValues.column(metaData("c" to typeName), 1).format)
    }

    @Test
    fun `a type name is matched case-insensitively`() {
        assertEquals(ColumnFormat.NUMBER, PostgresValues.column(metaData("c" to "NUMERIC"), 1).format)
        assertEquals(
            CellValue.Decimal(BigDecimal("1.5")),
            read(column("NUMERIC"), Cell(string = "1.5")),
        )
    }

    // -------------------------------------------------------------- integers

    @Test
    fun `an int8 at the far end of its range keeps every digit`() {
        assertEquals(
            CellValue.Integer(Long.MAX_VALUE),
            read(column("int8"), Cell(long = Long.MAX_VALUE)),
        )
        assertEquals(
            CellValue.Integer(Long.MIN_VALUE),
            read(column("int8"), Cell(long = Long.MIN_VALUE)),
        )
    }

    @Test
    fun `an int8 past what a double holds is not rounded to it`() {
        // 2^53 + 1. A Double cannot represent it, and would answer 9007199254740992.
        val value = 9_007_199_254_740_993L
        val cell = read(column("int8"), Cell(long = value))
        assertEquals(CellValue.Integer(value), cell)
        assertEquals("9007199254740993", (cell as CellValue.Integer).value.toString())
    }

    @ParameterizedTest(name = "{0} is read as a 64-bit integer")
    @ValueSource(strings = ["int2", "int4", "int8", "oid"])
    fun `every integer type is read wide, including the unsigned oid`(typeName: String) {
        // oid is unsigned 32-bit: 4294967295 does not fit in an Int and must not be
        // read through one.
        assertEquals(
            CellValue.Integer(4_294_967_295L),
            read(column(typeName), Cell(long = 4_294_967_295L)),
        )
    }

    // --------------------------------------------------------------- numerics

    @Test
    fun `a numeric keeps its declared scale rather than its shortest form`() {
        val cell = assertIs<CellValue.Decimal>(read(column("numeric"), Cell(string = "1.5000000000")))
        assertEquals(10, cell.value.scale())
        assertEquals("1.5000000000", cell.value.toPlainString())
    }

    @Test
    fun `a numeric with 38 digits of precision survives round trip`() {
        val text = "1234567890123456789012345678.9012345678"
        val cell = assertIs<CellValue.Decimal>(read(column("numeric"), Cell(string = text)))
        assertEquals(text, cell.value.toPlainString())
        assertEquals(38, cell.value.precision())
        assertEquals(10, cell.value.scale())
    }

    @Test
    fun `a numeric with more digits than a double can hold loses none of them`() {
        val text = "0.1234567890123456789012345678901234567890"
        val cell = assertIs<CellValue.Decimal>(read(column("numeric"), Cell(string = text)))
        assertEquals(text, cell.value.toPlainString())
        // The proof that this did not pass through a Double on the way.
        assertTrue(cell.value.toPlainString() != BigDecimal(text.toDouble()).toPlainString())
    }

    @ParameterizedTest(name = "a numeric holding {0} is kept as the server's own text")
    @ValueSource(strings = ["NaN", "Infinity", "-Infinity"])
    fun `the numeric values a BigDecimal cannot hold are kept verbatim`(text: String) {
        // getBigDecimal would throw on all three. Reading the server's text and
        // failing over to Text is what makes them survive.
        assertEquals(CellValue.Text(text), read(column("numeric"), Cell(string = text)))
    }

    @ParameterizedTest(name = "a {0} holding NaN is kept as text")
    @ValueSource(strings = ["float4", "float8"])
    fun `the float types take the same route as numeric`(typeName: String) {
        assertEquals(CellValue.Text("NaN"), read(column(typeName), Cell(string = "NaN")))
        assertEquals(
            CellValue.Decimal(BigDecimal("3.25")),
            read(column(typeName), Cell(string = "3.25")),
        )
    }

    @Test
    fun `an exponent the server wrote is parsed rather than rejected`() {
        val cell = assertIs<CellValue.Decimal>(read(column("float8"), Cell(string = "1.5e-7")))
        assertEquals(0, BigDecimal("0.00000015").compareTo(cell.value))
    }

    // --------------------------------------------------------------- booleans

    @ParameterizedTest(name = "a bool holding {0}")
    @ValueSource(booleans = [true, false])
    fun `a bool is read as one`(value: Boolean) {
        assertEquals(CellValue.Bool(value), read(column("bool"), Cell(bool = value)))
    }

    // ----------------------------------------------------------------- bytea

    @Test
    fun `bytea is PostgreSQL's own hexadecimal, lowercase and byte for byte`() {
        val cell = assertIs<CellValue.Binary>(
            read(column("bytea"), Cell(bytes = byteArrayOf(0x00, 0x0f, 0x7f, 0xff.toByte()))),
        )
        assertEquals("\\x000f7fff", cell.preview)
        assertEquals(4, cell.byteCount)
        assertTrue(!cell.truncated)
    }

    @Test
    fun `a bytea past the preview bound reports the whole length and says it was cut`() {
        val bytes = ByteArray(64) { 0xab.toByte() }
        val cell = assertIs<CellValue.Binary>(
            read(column("bytea"), Cell(bytes = bytes), ResultLimits(binaryPreviewBytes = 8)),
        )
        assertEquals("\\x" + "ab".repeat(8), cell.preview)
        assertEquals(64, cell.byteCount, "the count is the true length, not the preview's")
        assertTrue(cell.truncated)
    }

    @Test
    fun `an empty bytea is a value and not a null`() {
        val cell = assertIs<CellValue.Binary>(read(column("bytea"), Cell(bytes = ByteArray(0))))
        assertEquals("\\x", cell.preview)
        assertEquals(0, cell.byteCount)
        assertTrue(!cell.truncated)
    }

    // ------------------------------------------------------------ timestamptz

    @Test
    fun `a timestamptz is normalized to UTC, whatever zone the session rendered it in`() {
        val moment = OffsetDateTime.of(2026, 3, 14, 9, 26, 53, 589_793_000, ZoneOffset.ofHours(-7))
        assertEquals(
            CellValue.Text("2026-03-14 16:26:53.589793+00:00"),
            read(column("timestamptz"), Cell(string = "2026-03-14 09:26:53.589793-07", timestamp = moment)),
        )
    }

    @Test
    fun `a whole second is written without a fraction rather than with six zeroes`() {
        val moment = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        assertEquals(
            CellValue.Text("2026-01-01 00:00:00+00:00"),
            read(column("timestamptz"), Cell(string = "2026-01-01 00:00:00+00", timestamp = moment)),
        )
    }

    @ParameterizedTest(name = "timestamptz {0} keeps the server's spelling")
    @ValueSource(strings = ["infinity", "-infinity"])
    fun `the endless timestamps are not points in time and are left alone`(text: String) {
        assertEquals(CellValue.Text(text), read(column("timestamptz"), Cell(string = text)))
    }

    @Test
    fun `a timestamptz the driver cannot parse falls back to the text it sent`() {
        assertEquals(
            CellValue.Text("2026-03-14 09:26:53.589793-07"),
            read(column("timestamptz"), Cell(string = "2026-03-14 09:26:53.589793-07", timestamp = null)),
        )
    }

    @Test
    fun `the other temporal types are not rewritten, because their text is the value`() {
        // Only timestamptz is rendered in the session's zone. date, time and
        // timestamp mean the same thing on every machine already.
        assertEquals(CellValue.Text("2026-03-14"), read(column("date"), Cell(string = "2026-03-14")))
        assertEquals(CellValue.Text("09:26:53"), read(column("time"), Cell(string = "09:26:53")))
        assertEquals(
            CellValue.Text("2026-03-14 09:26:53"),
            read(column("timestamp"), Cell(string = "2026-03-14 09:26:53")),
        )
    }

    // ------------------------------------------------------------------ text

    @ParameterizedTest(name = "{0} arrives as the server's own text")
    @ValueSource(strings = ["text", "varchar", "uuid", "inet", "jsonb", "_int4", "int4range", "some_extension_type"])
    fun `a type with no special handling arrives verbatim, including one never heard of`(typeName: String) {
        val text = "whatever the server wrote"
        assertEquals(CellValue.Text(text), read(column(typeName), Cell(string = text)))
    }

    @Test
    fun `a text cell past the character bound is cut and says so`() {
        val text = "x".repeat(100)
        val cell = assertIs<CellValue.Text>(
            read(column("text"), Cell(string = text), ResultLimits(cellCharacters = 10)),
        )
        assertEquals("x".repeat(10), cell.value)
        assertTrue(cell.truncated)
    }

    @Test
    fun `a cut never falls between the halves of a character outside the BMP`() {
        // Each of these is two UTF-16 units, so a cut at 5 would land inside one.
        val cell = assertIs<CellValue.Text>(
            read(column("text"), Cell(string = "🜁".repeat(8)), ResultLimits(cellCharacters = 5)),
        )
        assertEquals("🜁".repeat(2), cell.value)
        assertTrue(cell.truncated)
    }

    // ------------------------------------------------------------------ null

    @ParameterizedTest(name = "a NULL {0} is CellValue.Null and not a zero value")
    @ValueSource(
        strings = [
            "int2", "int4", "int8", "oid", "numeric", "float4", "float8",
            "bool", "bytea", "timestamptz", "timestamp", "date", "text", "jsonb", "uuid",
        ],
    )
    fun `NULL in every type is null and never the type's empty value`(typeName: String) {
        // getLong answers 0 and getBoolean answers false for SQL NULL, which is why
        // wasNull is asked after the read rather than before it. An int8 NULL that
        // came back as 0 would be indistinguishable from a real zero in the grid and
        // in an export.
        assertEquals(CellValue.Null, read(column(typeName), Cell(isNull = true)))
    }

    // ----------------------------------------------------------------- fakes

    private fun column(typeName: String) = Column(
        name = "c",
        typeName = typeName,
        format = ColumnFormat.TEXT,
    )

    private fun read(column: Column, cell: Cell, limits: ResultLimits = this.limits): CellValue =
        PostgresValues.read(resultSet(cell), 1, column, limits)

    /**
     * One cell as pgjdbc would hand it over. Only the getter its type calls is
     * answered; asking for another one fails the test rather than returning a
     * default, which is what makes this a test of *how* the value is read.
     */
    private class Cell(
        val string: String? = null,
        val long: Long = 0L,
        val bool: Boolean = false,
        val bytes: ByteArray? = null,
        val timestamp: OffsetDateTime? = null,
        val isNull: Boolean = false,
    )

    private fun resultSet(vararg cells: Cell): ResultSet {
        var lastRead = 1
        val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
            fun cell(): Cell {
                val index = args!![0] as Int
                lastRead = index
                return cells[index - 1]
            }
            when (method.name) {
                "getString" -> cell().let { if (it.isNull) null else it.string }
                "getLong" -> cell().let { if (it.isNull) 0L else it.long }
                "getBoolean" -> cell().let { if (it.isNull) false else it.bool }
                "getBytes" -> cell().let { if (it.isNull) null else it.bytes }
                "getObject" -> cell().let { if (it.isNull) null else it.timestamp }
                "wasNull" -> cells[lastRead - 1].isNull
                else -> error("the fake result set was asked for ${method.name}, which it does not answer")
            }
        }
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
            handler,
        ) as ResultSet
    }

    /** [columns] is label to PostgreSQL type name, in order. */
    private fun metaData(vararg columns: Pair<String, String>): ResultSetMetaData {
        val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
            val column = columns[(args!![0] as Int) - 1]
            when (method.name) {
                "getColumnLabel" -> column.first
                "getColumnTypeName" -> column.second
                "getColumnCount" -> columns.size
                else -> error("the fake metadata was asked for ${method.name}, which it does not answer")
            }
        }
        return Proxy.newProxyInstance(
            ResultSetMetaData::class.java.classLoader,
            arrayOf(ResultSetMetaData::class.java),
            handler,
        ) as ResultSetMetaData
    }
}
