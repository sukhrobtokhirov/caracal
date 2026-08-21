package dev.caracal.app

import dev.caracal.core.history.ExecutionOutcome
import dev.caracal.core.history.ExecutionRecord
import dev.caracal.core.connections.ConnectionId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

/**
 * How an execution reads in the panel.
 *
 * The interesting cases are all absences. A statement that was cancelled has no row
 * count and no duration, and the difference between saying nothing and printing a
 * zero is the difference between "not measured" and "took no time and returned
 * nothing" — the second of which is a claim, and a false one.
 */
class HistoryFormatTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 8, 21)

    private fun day(text: String) = HistoryFormat.day(Instant.parse(text), zone)

    @Test
    fun `today and yesterday are named, and older days are dated`() {
        assertEquals("Today", HistoryFormat.day(today, today))
        assertEquals("Yesterday", HistoryFormat.day(today.minusDays(1), today))
        assertEquals("Tuesday 18 August", HistoryFormat.day(today.minusDays(3), today))
        // A date from another year without its year is a date the reader has to date
        // themselves, in a list where every other heading is from this one.
        assertTrue(HistoryFormat.day(today.minusYears(1), today).endsWith("2025"))
    }

    @Test
    fun `which day an execution belongs to is decided in the reader's own zone`() {
        // 23:30 UTC is already tomorrow in Berlin. A panel grouping by UTC would file
        // this evening's queries under a heading the user has not reached yet.
        assertEquals(LocalDate.of(2026, 8, 21), day("2026-08-20T23:30:00Z"))
        assertEquals("01:30:00", HistoryFormat.time(Instant.parse("2026-08-20T23:30:00Z"), zone))
    }

    @Test
    fun `a duration is written at the resolution someone reads it`() {
        assertEquals("8 ms", HistoryFormat.duration(8.milliseconds))
        assertEquals("1.5 s", HistoryFormat.duration(1_500.milliseconds))
        assertEquals("2m 5s", HistoryFormat.duration(125.seconds))
        assertNull(HistoryFormat.duration(null))
    }

    @Test
    fun `a row count that was never taken is absent rather than zero`() {
        assertEquals("1 row", HistoryFormat.rows(1))
        assertEquals("12,004 rows", HistoryFormat.rows(12_004))
        assertNull(HistoryFormat.rows(null))
    }

    @Test
    fun `the summary is built from the parts that have answers`() {
        val cancelled = ExecutionRecord(
            connectionId = ConnectionId("id-1"),
            statement = "SELECT 1",
            outcome = ExecutionOutcome.CANCELLED,
            executedAt = Instant.parse("2026-08-21T07:00:00Z"),
        )

        // Not "09:00:00 · — · — rows". A row of dashes is a template claiming three
        // things were measured on a statement where none of them were.
        assertEquals("09:00:00", HistoryFormat.summary(cancelled, zone))
        assertEquals(
            "09:00:00 · 8 ms · 3 rows",
            HistoryFormat.summary(
                cancelled.copy(duration = 8.milliseconds, rowCount = 3),
                zone,
            ),
        )
    }

    @Test
    fun `a preview is one line of a statement however it was laid out`() {
        val formatted = "SELECT id,\n       name\n  FROM customers\n WHERE active"

        assertEquals("SELECT id, name FROM customers WHERE active", HistoryFormat.preview(formatted))
        assertEquals("SELECT…", HistoryFormat.preview("SELECT everything", limit = 7))
    }
}
