package dev.caracal.app

import dev.caracal.core.export.CsvExportReport
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.ColumnFormat
import dev.caracal.core.result.Failure
import dev.caracal.core.result.QueryResult
import dev.caracal.core.sql.ExecutionTarget
import dev.caracal.core.sql.Statement
import dev.caracal.core.sql.TargetSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

/**
 * What the live region says, asserted as text rather than listened for.
 *
 * The rule these exist to hold is the one §4.9 attaches to the requirement rather
 * than the requirement itself: announce the outcome, never the result. A grid that
 * reads itself out is not accessible, it is unusable — and it is the kind of
 * regression that would never be noticed by anyone testing with their eyes.
 */
class AnnouncementsTest {

    private val target = ExecutionTarget(
        statement = Statement("select id, email from users", start = 0, end = 27),
        source = TargetSource.CURSOR,
    )

    private fun result(rows: Int) = QueryResult(
        columns = listOf(
            Column("id", "int8", ColumnFormat.NUMBER),
            Column("email", "text", ColumnFormat.TEXT),
        ),
        rows = List(rows) { listOf(CellValue.Integer(it.toLong()), CellValue.Text("a@b.test")) },
        duration = 8.milliseconds,
    )

    @Test
    fun `nothing is said until something has happened`() {
        assertNull(Announcements.of(EditorRun.Idle))
        // A statement starting is the user's own keypress read back at them. What
        // they cannot see is the moment it stops.
        assertNull(Announcements.of(EditorRun.Running(target)))
    }

    @Test
    fun `a finished query announces its shape and not its contents`() {
        val said = Announcements.of(EditorRun.Done(target, ResultGridState(result(rows = 3))))

        assertEquals("Finished. 3 rows · 8.00 ms", said)
    }

    /**
     * The assertion this file exists for. Ten thousand rows are ten thousand rows,
     * not ten thousand rows read out.
     */
    @Test
    fun `a large result is still one short sentence`() {
        val said = Announcements.of(EditorRun.Done(target, ResultGridState(result(rows = 5_000))))

        assertTrue(said!!.length < 80, "the announcement was $said")
        assertFalse(said.contains("a@b.test"), "a cell value reached the announcement")
    }

    @Test
    fun `cancelling says it was stopped, not that it failed`() {
        val said = Announcements.of(EditorRun.Cancelled)

        assertTrue(said!!.startsWith("Cancelled."))
        assertFalse(said.contains("fail", ignoreCase = true))
    }

    @Test
    fun `a failure announces the message the banner is already showing`() {
        val failure = Failure("query_failed", "relation \"users\" does not exist")

        assertEquals(
            "Failed. relation \"users\" does not exist",
            Announcements.of(EditorRun.Failed(failure)),
        )
    }

    @Test
    fun `an export announces only when it is over`() {
        assertNull(Announcements.of(ExportRun.Idle))
        assertNull(Announcements.of(ExportRun.Running(Path.of("/tmp/out.csv"))))

        val done = ExportRun.Done(
            path = Path.of("/tmp/out.csv"),
            report = CsvExportReport(rows = 12, bytes = 480, duration = 12.milliseconds),
        )
        assertTrue(Announcements.of(done)!!.contains("out.csv"))
        assertEquals("Export cancelled. No file was written.", Announcements.of(ExportRun.Cancelled))
    }
}
