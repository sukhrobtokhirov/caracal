package dev.caracal.core.export

import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.result.FakeQueryFacet
import dev.caracal.core.result.rows
import dev.caracal.engine.api.CellValue
import dev.caracal.engine.api.ColumnDescriptor
import dev.caracal.engine.api.EngineError
import dev.caracal.engine.api.ResultDescriptor
import dev.caracal.engine.api.Row
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.TruncationReason
import dev.caracal.engine.api.ValueDetail
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The export loop, held to its two promises without a database.
 *
 * It streams — the row that is being written is the row that was just read, and no
 * more of the result than that is ever in hand — and it stops at the budget it was
 * given rather than at the end of whatever the server had. Both used to be
 * PostgreSQL's to keep, reachable from `:core` only through `LegacySqlAdapter`; issue
 * #4 moved them here, where §12 says export belongs, and where they can be asked
 * about with a fake engine.
 */
class CsvStreamTest {

    @Test
    fun `a result is a header and one record per row`() = runTest {
        val out = StringBuilder()

        val report = CsvStream.write(facetOf(rows(cells("1", "one"), cells("2", "two"))), "SELECT * FROM t", out)

        assertEquals("c0,c1\r\n1,one\r\n2,two\r\n", out.toString())
        assertEquals(2, report.rows)
        assertEquals(ExportStop.COMPLETE, report.stopped)
        assertTrue(report.complete)
    }

    @Test
    fun `the byte count is the file measured as UTF-8`() = runTest {
        val out = StringBuilder()

        val report = CsvStream.write(facetOf(rows(cells("café"))), "SELECT * FROM t", out)

        assertEquals(out.toString().toByteArray(Charsets.UTF_8).size.toLong(), report.bytes)
    }

    // --- Streaming, not assembling ----------------------------------------------

    @Test
    fun `a row is written before the next one is read`() = runTest {
        // The whole reason the loop is here rather than behind a materializing call.
        // If the export assembled first, every row would be produced before the first
        // one was written, and this would see 10 against 0 on the first record.
        var produced = 0
        val writtenAt = mutableListOf<Int>()
        val out = object : Appendable {
            override fun append(value: CharSequence?) = also { record(value) }
            override fun append(value: CharSequence?, start: Int, end: Int) = also { record(value) }
            override fun append(value: Char) = also { if (value == '\n') writtenAt += produced }
            private fun record(value: CharSequence?) {
                if (value?.contains('\n') == true) writtenAt += produced
            }
        }
        val facet = FakeQueryFacet {
            flow {
                emit(
                    StatementOutcome.Rows(
                        descriptor = ResultDescriptor(listOf(ColumnDescriptor("i", "int4"))),
                        rows = flow {
                            repeat(10) { index ->
                                produced++
                                emit(Row(listOf(CellValue.Text(index.toString()))))
                            }
                        },
                    ),
                )
            }
        }

        CsvStream.write(facet, "SELECT i FROM t", out)

        // One entry per record ended: the header at zero rows produced, then each row
        // at exactly the count that had been read when it was written.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), writtenAt)
    }

    // --- Budgets ------------------------------------------------------------------

    @Test
    fun `the row budget is the engine's to enforce and is passed to it`() = runTest {
        val facet = facetOf(rows(cells("1")))

        CsvStream.write(facet, "SELECT * FROM t", StringBuilder(), limits = ExportLimits(rows = 7))

        assertEquals(7L, facet.lastRequest?.maxRows)
    }

    @Test
    fun `a result the engine cut at the row budget is reported as cut`() = runTest {
        val facet = FakeQueryFacet {
            flow {
                emit(rows(cells("1"), cells("2")))
                emit(StatementOutcome.Truncated(TruncationReason.ROW_LIMIT))
            }
        }

        val report = CsvStream.write(facet, "SELECT * FROM t", StringBuilder(), limits = ExportLimits(rows = 2))

        assertEquals(2, report.rows)
        assertEquals(ExportStop.ROW_LIMIT, report.stopped)
        assertTrue(!report.complete)
    }

    @Test
    fun `an export past its byte budget stops at the end of a record`() = runTest {
        val out = StringBuilder()
        val many = (1..100).map { cells(it.toString()) }.toTypedArray()

        val report = CsvStream.write(
            facetOf(rows(*many)),
            "SELECT * FROM t",
            out,
            limits = ExportLimits(bytes = 20),
        )

        assertEquals(ExportStop.SIZE_LIMIT, report.stopped)
        assertTrue(out.endsWith("\r\n"), "stopped inside a record: ${out.takeLast(10)}")
        assertTrue(report.rows in 1..99, "wrote ${report.rows} of 100 rows")
    }

    @Test
    fun `a byte budget the file has not reached does not stop it`() = runTest {
        val report = CsvStream.write(
            facetOf(rows(cells("1"))),
            "SELECT * FROM t",
            StringBuilder(),
            limits = ExportLimits(bytes = 1_000, duration = 5.minutes),
        )

        assertEquals(ExportStop.COMPLETE, report.stopped)
    }

    // --- What is asked of the engine ---------------------------------------------

    @Test
    fun `an export asks for whole values, because a prefix in a file is a lie`() = runTest {
        val facet = facetOf(rows(cells("1")))

        CsvStream.write(facet, "SELECT * FROM t", StringBuilder())

        assertEquals(ValueDetail.FULL, facet.lastRequest?.values)
    }

    @Test
    fun `an export is given longer than the session's own statement limit`() = runTest {
        val facet = facetOf(rows(cells("1")))

        CsvStream.write(facet, "SELECT * FROM t", StringBuilder(), limits = ExportLimits(duration = 5.minutes))

        val timeout = facet.lastRequest?.timeout
        assertTrue(
            timeout != null && timeout > 5.minutes,
            "the statement's deadline must outlast the loop's, or the polite stop never wins: $timeout",
        )
    }

    @Test
    fun `what runs is the statement the eligibility check looked at`() = runTest {
        // Not the script it was cut from: otherwise the statement that was vetted and
        // the statement that reaches the server could differ.
        val facet = facetOf(rows(cells("1")))

        CsvStream.write(facet, "  SELECT * FROM t  ", StringBuilder())

        assertEquals("SELECT * FROM t", facet.lastRequest?.sql)
        assertEquals(2, facet.lastRequest?.sourceOffset, "the statement's own offset was lost")
    }

    // --- Refusals -------------------------------------------------------------------

    @Test
    fun `a statement that modifies data is refused before anything is asked of the engine`() {
        var asked = false
        val facet = FakeQueryFacet {
            asked = true
            flow { }
        }

        val error = assertThrows<DbException> {
            runBlocking { CsvStream.write(facet, "DELETE FROM invoices", StringBuilder()) }
        }.error

        assertIs<DbError.ExportUnavailable>(error)
        assertEquals(ExportRefusal.MODIFIES_DATA.message, error.message)
        assertTrue(!asked, "ran a statement that had already been refused")
    }

    @Test
    fun `a script of several statements is refused`() {
        val error = assertThrows<DbException> {
            runBlocking { CsvStream.write(facetOf(rows(cells("1"))), "SELECT 1; SELECT 2", StringBuilder()) }
        }.error

        assertEquals(ExportRefusal.SEVERAL_STATEMENTS.message, error.message)
    }

    @Test
    fun `a statement that produced no result set is refused after it ran, and writes no header`() {
        val out = StringBuilder()

        val error = assertThrows<DbException> {
            runBlocking {
                CsvStream.write(
                    FakeQueryFacet { flow { emit(StatementOutcome.UpdateCount(count = 0)) } },
                    "SELECT 1",
                    out,
                )
            }
        }.error

        assertIs<DbError.ExportUnavailable>(error)
        assertEquals(ExportRefusal.NO_ROWS.message, error.message)
        assertEquals("", out.toString(), "wrote a header for a result that does not exist")
    }

    @Test
    fun `a statement the server rejected fails the export rather than writing a short file`() {
        val classified = DbException(DbError.QueryFailed("relation \"nope\" does not exist", sqlState = "42P01"))

        val thrown = assertThrows<DbException> {
            runBlocking {
                CsvStream.write(
                    FakeQueryFacet {
                        flow {
                            emit(
                                StatementOutcome.Failed(
                                    EngineError(message = "relation \"nope\" does not exist", cause = classified),
                                ),
                            )
                        }
                    },
                    "SELECT * FROM nope",
                    StringBuilder(),
                )
            }
        }

        assertEquals("42P01", assertIs<DbError.QueryFailed>(thrown.error).sqlState)
    }

    private fun facetOf(outcome: StatementOutcome) = FakeQueryFacet { flow { emit(outcome) } }

    private fun cells(vararg values: String) = values.map { CellValue.Text(it) }
}
