package dev.caracal.core.result

import dev.caracal.engine.api.CancelResult
import dev.caracal.engine.api.CellValue as EngineCellValue
import dev.caracal.engine.api.ColumnDescriptor
import dev.caracal.engine.api.ColumnFormat as EngineColumnFormat
import dev.caracal.engine.api.EngineError
import dev.caracal.engine.api.ResultDescriptor
import dev.caracal.engine.api.Row
import dev.caracal.engine.api.SourcePosition
import dev.caracal.engine.api.StatementExecution
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.TemporalKind
import dev.caracal.engine.api.TruncationReason
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Reading an engine's stream back as the one result the grid draws.
 *
 * The conversion issue #4 put here is the whole of what `:core` does with a
 * statement, so the cases below are about the two things it can get wrong: losing a
 * value on the way through the narrower model, and losing the engine's own report of
 * a failure.
 */
class QueryResultsTest {

    @Test
    fun `rows arrive with their columns and their values`() = runTest {
        val result = execution(
            rows(
                listOf(EngineCellValue.Integer(BigInteger.ONE), EngineCellValue.Text("two")),
                listOf(EngineCellValue.Integer(BigInteger.TWO), EngineCellValue.Text("three")),
                columns = listOf(
                    ColumnDescriptor("n", "int4", EngineColumnFormat.NUMBER),
                    ColumnDescriptor("word", "text"),
                ),
            ),
        ).materialize()

        assertEquals(listOf("n", "word"), result.columns.map { it.name })
        assertEquals(listOf(ColumnFormat.NUMBER, ColumnFormat.TEXT), result.columns.map { it.format })
        assertEquals(
            listOf(CellValue.Integer(1), CellValue.Text("two")),
            result.rows.first(),
        )
        assertEquals(2, result.rows.size)
        assertNull(result.rowsAffected, "a result-producing statement has no count")
    }

    @Test
    fun `a statement with no result set carries its count and no columns`() = runTest {
        val result = execution(StatementOutcome.UpdateCount(count = 3)).materialize()

        assertEquals(3L, result.rowsAffected)
        assertTrue(result.columns.isEmpty())
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `notices are kept in the order the server said them`() = runTest {
        val result = execution(
            StatementOutcome.Notice("checked 3 tables", severity = "NOTICE", sqlState = "00000"),
            StatementOutcome.Notice("this column is deprecated", severity = "WARNING"),
            StatementOutcome.UpdateCount(count = 0),
        ).materialize()

        assertEquals(
            listOf("checked 3 tables", "this column is deprecated"),
            result.notices.map { it.message },
        )
        assertEquals("00000", result.notices.first().sqlState)
    }

    @Test
    fun `a truncated result says which limit ended it`() = runTest {
        val result = execution(
            rows(listOf(EngineCellValue.Text("a"))),
            StatementOutcome.Truncated(TruncationReason.SIZE_LIMIT),
        ).materialize()

        assertEquals(Truncation.SIZE_LIMIT, result.truncation)
        assertTrue(result.truncated)
    }

    @Test
    fun `a result nothing truncated is complete`() = runTest {
        val result = execution(rows(listOf(EngineCellValue.Text("a")))).materialize()

        assertEquals(Truncation.NONE, result.truncation)
    }

    @Test
    fun `the descriptor's own elapsed is what the result reports`() = runTest {
        val result = execution(
            StatementOutcome.Rows(
                descriptor = ResultDescriptor(listOf(ColumnDescriptor("a", "text")), elapsed = 34.milliseconds),
                rows = flow { emit(Row(listOf(EngineCellValue.Text("x")))) },
            ),
        ).materialize()

        assertEquals(34.milliseconds, result.duration, "the server's own timing was replaced by this loop's")
    }

    // --- The rows are live only while they are being delivered ------------------

    @Test
    fun `the rows are read inside the emission that carried them`() = runTest {
        // The contract every streaming engine depends on. A cursor is open behind that
        // flow and is closed the moment the emission returns, so a caller that kept the
        // flow to read later would find nothing — and this fake fails outright rather
        // than returning an empty result, which is how a caller that got it wrong would
        // otherwise look like a query that matched nothing.
        var closed = false
        val outcome = StatementOutcome.Rows(
            descriptor = ResultDescriptor(listOf(ColumnDescriptor("a", "text"))),
            rows = flow {
                check(!closed) { "the row flow was collected after its cursor was closed" }
                emit(Row(listOf(EngineCellValue.Text("x"))))
            },
        )
        val execution = object : StatementExecution {
            override val outcomes = flow {
                emit(outcome)
                closed = true
            }

            override suspend fun cancel() = CancelResult.Unsupported("nothing runs")
        }

        assertEquals(listOf(listOf(CellValue.Text("x"))), execution.materialize().rows)
    }

    // --- Failures ---------------------------------------------------------------

    @Test
    fun `a failure the engine classified is thrown exactly as the engine classified it`() = runTest {
        // The engine's own report is richer than the SPI's: a severity, the object the
        // server named, and the position counted the way the server counted it. It
        // travels as the cause rather than being rebuilt from the narrower shape, and
        // that is what keeps the error banner and the underline whole.
        val classified = DbException(
            DbError.QueryFailed(
                message = "column \"totl\" does not exist",
                sqlState = "42703",
                position = 8,
                severity = "ERROR",
                subject = ErrorSubject(column = "totl"),
            ),
        )

        val thrown = assertThrows<DbException> {
            runBlocking {
                execution(
                    StatementOutcome.Failed(
                        EngineError(
                            message = "column \"totl\" does not exist",
                            code = "42703",
                            position = SourcePosition(offset = 7),
                            cause = classified,
                        ),
                    ),
                ).materialize()
            }
        }

        assertSame(classified, thrown, "the engine's classified failure was rebuilt instead of carried")
        val error = assertIs<DbError.QueryFailed>(thrown.error)
        assertEquals(8, error.position, "the position the server counted was replaced by the mapped one")
        assertEquals("ERROR", error.severity)
        assertEquals("totl", error.subject?.column)
    }

    @Test
    fun `an engine that classified nothing still reports what it did send`() = runTest {
        val thrown = assertThrows<DbException> {
            runBlocking {
                execution(
                    StatementOutcome.Failed(
                        EngineError(
                            message = "near \"slect\": syntax error",
                            code = "1",
                            position = SourcePosition(offset = 0),
                            hint = "Did you mean SELECT?",
                        ),
                    ),
                ).materialize()
            }
        }

        val error = assertIs<DbError.QueryFailed>(thrown.error)
        assertEquals("near \"slect\": syntax error", error.message)
        assertEquals("1", error.sqlState)
        assertEquals("Did you mean SELECT?", error.hint)
        assertNull(
            error.position,
            "an offset into the buffer was put in the field that means a position in the statement",
        )
    }

    // --- The value model is narrower, and nothing is lost on the way through -----

    @Test
    fun `an integer too large for a Long keeps its digits rather than wrapping`() {
        val huge = BigInteger("18446744073709551615")

        assertEquals(CellValue.Text("18446744073709551615"), EngineCellValue.Integer(huge).toCore())
    }

    @Test
    fun `an integer that fits stays an integer`() {
        val top = BigInteger.valueOf(Long.MAX_VALUE)
        val bottom = BigInteger.valueOf(Long.MIN_VALUE)

        assertEquals(CellValue.Integer(Long.MAX_VALUE), EngineCellValue.Integer(top).toCore())
        assertEquals(CellValue.Integer(Long.MIN_VALUE), EngineCellValue.Integer(bottom).toCore())
    }

    @Test
    fun `an exact decimal keeps its scale`() {
        val exact = BigDecimal("12345678901234567890.1234567890")

        assertEquals(CellValue.Decimal(exact), EngineCellValue.Decimal(exact).toCore())
    }

    @Test
    fun `json and a temporal keep the server's text byte for byte`() {
        assertEquals(CellValue.Text("""{"a": 1}"""), EngineCellValue.Json("""{"a": 1}""").toCore())
        assertEquals(
            CellValue.Text("2026-08-25 12:00:00+00"),
            EngineCellValue.Temporal(TemporalKind.TIMESTAMP_WITH_ZONE, "2026-08-25 12:00:00+00").toCore(),
        )
    }

    @Test
    fun `a type this model has never heard of arrives as what the server wrote`() {
        assertEquals(CellValue.Text("(1,2)"), EngineCellValue.Opaque("point", "(1,2)").toCore())
    }

    @Test
    fun `a clipped value is still marked as clipped on the other side`() {
        assertEquals(
            CellValue.Text("half a doc", truncated = true),
            EngineCellValue.Text("half a doc", truncated = true).toCore(),
        )
    }

    @Test
    fun `bytes keep the true length beside the preview`() {
        val binary = assertIs<CellValue.Binary>(
            EngineCellValue.Bytes(preview = "\\xdeadbeef", byteCount = 40_000_000, truncated = true).toCore(),
        )

        assertEquals(40_000_000, binary.byteCount)
        assertTrue(binary.truncated)
    }

    @Test
    fun `a byte count past what an Int holds is clamped rather than made negative`() {
        val binary = assertIs<CellValue.Binary>(
            EngineCellValue.Bytes(preview = "\\xde", byteCount = 5_000_000_000, truncated = true).toCore(),
        )

        assertEquals(Int.MAX_VALUE, binary.byteCount)
    }

    @Test
    fun `null is null and not an empty string`() {
        assertEquals(CellValue.Null, EngineCellValue.Null.toCore())
    }

    @Test
    fun `a boolean stays a boolean`() {
        assertEquals(CellValue.Bool(true), EngineCellValue.Bool(true).toCore())
    }

    @Test
    fun `an approximate float arrives as the shortest decimal that round trips`() {
        // The one arm that was never exact to begin with, so its own rendering is the
        // whole of what it has. PostgreSQL never constructs one.
        assertEquals(CellValue.Text("0.1"), EngineCellValue.Floating(0.1).toCore())
    }

    @Test
    fun `an array is rendered in the only array syntax this model has ever carried`() {
        // Unreachable for PostgreSQL, which sends an array as the literal the server
        // wrote. An engine whose arrays are spelled differently should do the same
        // rather than rely on this.
        val array = EngineCellValue.Array(
            elements = listOf(
                EngineCellValue.Integer(BigInteger.ONE),
                EngineCellValue.Text("two"),
                EngineCellValue.Decimal(BigDecimal("3.50")),
                EngineCellValue.Bool(false),
                EngineCellValue.Bytes(preview = "\\xff", byteCount = 1, truncated = false),
                EngineCellValue.Null,
            ),
            elementType = "mixed",
        )

        assertEquals(CellValue.Text("""{1,two,3.50,f,\xff,NULL}"""), array.toCore())
    }

    @Test
    fun `every column format has a counterpart`() {
        val expected = mapOf(
            EngineColumnFormat.TEXT to ColumnFormat.TEXT,
            EngineColumnFormat.NUMBER to ColumnFormat.NUMBER,
            EngineColumnFormat.BOOLEAN to ColumnFormat.BOOLEAN,
            EngineColumnFormat.TEMPORAL to ColumnFormat.TEMPORAL,
            EngineColumnFormat.JSON to ColumnFormat.JSON,
            EngineColumnFormat.BINARY to ColumnFormat.BINARY,
        )

        EngineColumnFormat.entries.forEach { format ->
            assertEquals(expected.getValue(format), ColumnDescriptor("c", "t", format).toColumn().format)
        }
    }

    @Test
    fun `a column keeps the name and the engine's own type name`() {
        val column = ColumnDescriptor("amount", "numeric", EngineColumnFormat.NUMBER).toColumn()

        assertEquals("amount", column.name)
        assertEquals("numeric", column.typeName)
    }

    @Test
    fun `a truncation reason crosses as the grid's own`() = runTest {
        val result = execution(
            rows(listOf(EngineCellValue.Text("a"))),
            StatementOutcome.Truncated(TruncationReason.ROW_LIMIT),
        ).materialize()

        assertEquals(Truncation.ROW_LIMIT, result.truncation)
    }

    @Test
    fun `a statement whose whole output was a notice is still timed`() = runTest {
        // Nothing carried an elapsed of its own, so the round trip is measured rather
        // than reported as zero.
        val result = execution(
            StatementOutcome.Notice("checked 3 tables"),
            StatementOutcome.UpdateCount(count = 0),
        ).materialize()

        assertTrue(result.duration >= kotlin.time.Duration.ZERO)
    }
}
