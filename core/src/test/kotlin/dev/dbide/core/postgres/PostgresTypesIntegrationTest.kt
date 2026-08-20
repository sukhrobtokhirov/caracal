package dev.dbide.core.postgres

import dev.dbide.core.connections.Secret
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.ResultLimits
import dev.dbide.core.result.Truncation
import java.math.BigDecimal
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Whether what the grid shows is what PostgreSQL holds.
 *
 * This is the milestone's trust contract, and it is asked of a real server
 * because the interesting answers all come from the driver: which values it
 * refuses to convert, which it converts wrongly, and which it renders differently
 * depending on where the machine is.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DBIDE_INTEGRATION", matches = "1")
class PostgresTypesIntegrationTest {

    // --- Numbers, exactly -----------------------------------------------------

    @Test
    fun `an int8 past the range a double could hold keeps every digit`() {
        // 2^53 + 1. The source plan sent this as a string because JavaScript would
        // have rounded it to 2^53; a Long holds it, so it stays a number.
        assertEquals(CellValue.Integer(9007199254740993L), one("9007199254740993::int8"))
        assertEquals(CellValue.Integer(Long.MIN_VALUE), one("(-9223372036854775808)::int8"))
    }

    @Test
    fun `a numeric keeps its scale and its digits`() {
        assertEquals(
            CellValue.Decimal(BigDecimal("1234567890.00000001")),
            one("1234567890.00000001::numeric"),
        )
        // Trailing zeroes are part of the value PostgreSQL stored, not noise.
        assertEquals(CellValue.Decimal(BigDecimal("1.00000")), one("1.00000::numeric(10,5)"))
    }

    @Test
    fun `numeric NaN arrives as itself rather than as an error`() {
        // getBigDecimal throws on this one, which is why the encoder reads the text.
        assertEquals(CellValue.Text("NaN"), one("'NaN'::numeric"))
    }

    @Test
    fun `float infinities and NaN survive`() {
        assertEquals(CellValue.Text("Infinity"), one("'Infinity'::float8"))
        assertEquals(CellValue.Text("-Infinity"), one("'-Infinity'::float8"))
        assertEquals(CellValue.Text("NaN"), one("'NaN'::float8"))
        assertEquals(CellValue.Decimal(BigDecimal("0.1")), one("0.1::float8"))
    }

    // --- Nothing, and almost nothing ------------------------------------------

    @Test
    fun `an empty string is not NULL`() {
        assertEquals(CellValue.Text(""), one("''::text"))
        assertEquals(CellValue.Null, one("NULL::text"))
        assertNotEquals(one("''::text"), one("NULL::text"))
    }

    @Test
    fun `a NULL of any type is Null and not a zero`() {
        // getLong and getBoolean answer 0 and false for SQL NULL, so a check in the
        // wrong order turns every NULL integer into a zero.
        assertEquals(CellValue.Null, one("NULL::int8"))
        assertEquals(CellValue.Null, one("NULL::bool"))
        assertEquals(CellValue.Null, one("NULL::numeric"))
        assertEquals(CellValue.Null, one("NULL::bytea"))
        assertEquals(CellValue.Null, one("NULL::timestamptz"))
    }

    @Test
    fun `a zero-length bytea is a value, not an absence`() {
        assertEquals(CellValue.Binary("\\x", byteCount = 0, truncated = false), one("''::bytea"))
    }

    // --- Binary ---------------------------------------------------------------

    @Test
    fun `bytea arrives as PostgreSQL's own hexadecimal`() {
        assertEquals(CellValue.Binary("\\x0001ff", byteCount = 3, truncated = false), one("'\\x0001ff'::bytea"))
    }

    @Test
    fun `a large bytea is previewed and reports its true size`() {
        val value = one("repeat('a', 5000)::bytea", limits = ResultLimits(binaryPreviewBytes = 8))

        assertEquals(CellValue.Binary("\\x6161616161616161", byteCount = 5000, truncated = true), value)
    }

    // --- Time -----------------------------------------------------------------

    @Test
    fun `a timestamptz reads the same in any time zone the machine happens to be in`() {
        // The whole reason this type is normalized. PostgreSQL renders it in the
        // session's TimeZone, which pgjdbc takes from the JVM default at connect
        // time, so without this the same row reads differently on two laptops.
        val inUtc = withDefaultTimeZone("UTC") { one("'2024-01-15 12:00:00+00'::timestamptz") }
        val inLosAngeles = withDefaultTimeZone("America/Los_Angeles") {
            one("'2024-01-15 12:00:00+00'::timestamptz")
        }

        assertEquals(CellValue.Text("2024-01-15 12:00:00+00:00"), inUtc)
        assertEquals(inUtc, inLosAngeles)
    }

    @Test
    fun `a timestamptz keeps microseconds and drops nothing`() {
        assertEquals(
            CellValue.Text("2024-01-15 12:00:00.123456+00:00"),
            withDefaultTimeZone("UTC") { one("'2024-01-15 12:00:00.123456+00'::timestamptz") },
        )
    }

    @Test
    fun `an endless timestamp keeps the server's spelling`() {
        // Legal values that are not points in time, and not convertible to one.
        assertEquals(CellValue.Text("infinity"), one("'infinity'::timestamptz"))
        assertEquals(CellValue.Text("-infinity"), one("'-infinity'::timestamptz"))
    }

    @Test
    fun `types without a time zone keep the server's text exactly`() {
        assertEquals(CellValue.Text("2024-01-15 12:00:00"), one("'2024-01-15 12:00:00'::timestamp"))
        assertEquals(CellValue.Text("2024-01-15"), one("'2024-01-15'::date"))
        assertEquals(CellValue.Text("12:34:56.5"), one("'12:34:56.5'::time"))
        assertEquals(CellValue.Text("1 day 02:00:00"), one("'1 day 2 hours'::interval"))
    }

    // --- Everything else the server can spell ---------------------------------

    @Test
    fun `structured types arrive as the server's own text`() {
        assertEquals(CellValue.Text("{{1,2},{3,4}}"), one("'{{1,2},{3,4}}'::int4[]"))
        assertEquals(CellValue.Text("""{"a": [1, 2], "b": null}"""), one("""'{"b":null,"a":[1,2]}'::jsonb"""))
        assertEquals(CellValue.Text("""{"b":null,"a":[1,2]}"""), one("""'{"b":null,"a":[1,2]}'::json"""))
        assertEquals(CellValue.Text("42"), one("'42'::jsonb"))
        assertEquals(CellValue.Text("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"), one("'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid"))
        assertEquals(CellValue.Text("192.168.0.0/24"), one("'192.168.0.0/24'::cidr"))
        assertEquals(CellValue.Text("[1,10)"), one("'[1,10)'::int4range"))
        assertEquals(CellValue.Text("(1,two)"), one("row(1, 'two')::pair"))
    }

    @Test
    fun `a type this build has never heard of still arrives intact`() {
        // An enum created for this test. Nothing in the encoder knows about it, and
        // it works anyway, which is the point of defaulting to the server's text.
        val result = query("SELECT 'shipped'::delivery_state")

        assertEquals(CellValue.Text("shipped"), result.rows.single().single())
        assertEquals("delivery_state", result.columns.single().typeName)
        assertEquals(ColumnFormat.TEXT, result.columns.single().format)
    }

    // --- Columns --------------------------------------------------------------

    @Test
    fun `a column carries PostgreSQL's type name and a display hint`() {
        val result = query(
            "SELECT 1::int8 AS n, 1.5::numeric AS d, true AS b, 'x'::text AS t, " +
                "'{}'::jsonb AS j, ''::bytea AS y, now() AS ts",
        )

        assertEquals(
            listOf(
                Column("n", "int8", ColumnFormat.NUMBER),
                Column("d", "numeric", ColumnFormat.NUMBER),
                Column("b", "bool", ColumnFormat.BOOLEAN),
                Column("t", "text", ColumnFormat.TEXT),
                Column("j", "jsonb", ColumnFormat.JSON),
                Column("y", "bytea", ColumnFormat.BINARY),
                Column("ts", "timestamptz", ColumnFormat.TEMPORAL),
            ),
            result.columns,
        )
    }

    @Test
    fun `duplicate column names are kept and addressed by position`() {
        // Valid SQL. A grid keyed by name would show one column twice or lose one.
        val result = query("SELECT 1 AS a, 2 AS a")

        assertEquals(listOf("a", "a"), result.columns.map { it.name })
        assertEquals(listOf(CellValue.Integer(1), CellValue.Integer(2)), result.rows.single())
    }

    // --- Bounds ---------------------------------------------------------------

    @Test
    fun `an oversized cell is previewed and says it was cut`() {
        val value = one("repeat('x', 5000)::text", limits = ResultLimits(cellCharacters = 10))

        assertEquals(CellValue.Text("xxxxxxxxxx", truncated = true), value)
    }

    @Test
    fun `a result of few but enormous rows stops on size, not on rows`() {
        val result = query(
            "SELECT repeat('x', 1000) FROM generate_series(1, 100)",
            limits = ResultLimits(rows = 1_000, totalCharacters = 3_000),
        )

        assertEquals(Truncation.SIZE_LIMIT, result.truncation)
        assertTrue(result.truncated)
        assertTrue(result.rows.size < 100, "read ${result.rows.size} rows past the character budget")
    }

    @Test
    fun `a result of many small rows stops on rows`() {
        val result = query("SELECT 1 FROM generate_series(1, 100)", limits = ResultLimits(rows = 10))

        assertEquals(Truncation.ROW_LIMIT, result.truncation)
        assertEquals(10, result.rows.size)
    }

    @Test
    fun `a result within both bounds is not truncated`() {
        val result = query("SELECT 1 FROM generate_series(1, 10)", limits = ResultLimits(rows = 10))

        assertEquals(Truncation.NONE, result.truncation)
        assertEquals(10, result.rows.size)
    }

    private fun one(expression: String, limits: ResultLimits = ResultLimits()): CellValue =
        query("SELECT $expression", limits).rows.single().single()

    private fun query(sql: String, limits: ResultLimits = ResultLimits()) = runBlocking {
        val dataSource = PostgresDataSources.create(config())
        try {
            PostgresAdapter(dataSource, Redaction(config().secrets()), limits = limits).execute(sql)
        } finally {
            dataSource.close()
        }
    }

    /** Runs [body] as if the machine were somewhere else. */
    private fun <T> withDefaultTimeZone(zone: String, body: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            return body()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun config() = PostgresConnectionConfig(
        host = postgres.host,
        port = postgres.firstMappedPort,
        database = postgres.databaseName,
        user = postgres.username,
        password = Secret(postgres.password),
    )

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @JvmStatic
        @BeforeAll
        fun seed() {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TYPE delivery_state AS ENUM ('packed', 'shipped')")
                    statement.execute("CREATE TYPE pair AS (n int, word text)")
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() = postgres.stop()
    }
}
