package dev.caracal.app

import dev.caracal.core.history.ExecutionOutcome
import dev.caracal.core.history.ExecutionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration

/**
 * How an execution is written in the history panel.
 *
 * Separate from the composables for the reason [RedisFormat] is: the same execution
 * appears as a row, as an expanded entry, and inside a day heading, and three
 * spellings of one duration is how a panel stops reading as one list.
 *
 * Everything here takes the day and the zone it should be read in rather than asking
 * the system for them. A heading that says "Today" is a claim about the machine's
 * clock, and a function that reads that clock itself cannot be tested for the hour it
 * gets wrong — which is the hour either side of midnight, the one where it matters.
 */
object HistoryFormat {

    /**
     * The heading a day's executions sit under.
     *
     * Today and yesterday get their names, because that is what a person calls them
     * and because those two are most of what anyone scrolls through. Anything older
     * gets a date, and one from another year gets the year with it — "3 January" in
     * an August window is a date the reader has to date themselves.
     */
    fun day(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> {
            val pattern = if (day.year == today.year) "EEEE d MMMM" else "d MMMM yyyy"
            // English, deliberately, and not the machine's locale. Every other word
            // in this panel is English — "Today", "rows", "cancelled" — and a heading
            // reading "Dienstag 18 August" over rows labelled "3 rows" is a window
            // that has been translated halfway. Localizing is a decision to take for
            // the whole application at once, not one to make here by default.
            day.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
        }
    }

    /** Which day an execution belongs to, in the reader's own zone. */
    fun day(at: Instant, zone: ZoneId): LocalDate = at.atZone(zone).toLocalDate()

    /** The time of day an execution was sent, to the second. */
    fun time(at: Instant, zone: ZoneId): String = TIME.format(at.atZone(zone))

    /**
     * How long it took, or nothing at all.
     *
     * A statement that was never timed shows no duration rather than a zero. Zero
     * milliseconds is a claim, and the row it would appear on is one where nothing
     * was measured.
     */
    fun duration(duration: Duration?): String? = when {
        duration == null -> null
        duration.inWholeMilliseconds < 1_000 -> "${duration.inWholeMilliseconds} ms"
        duration.inWholeSeconds < 60 -> "%.1f s".format(duration.inWholeMilliseconds / 1000.0)
        else -> "${duration.inWholeSeconds / 60}m ${duration.inWholeSeconds % 60}s"
    }

    /**
     * What the statement did to rows, or nothing when it did not get that far.
     *
     * The word is deliberately vague, because the number is two different facts: rows
     * returned for a `SELECT`, rows affected for an `UPDATE`. Which one it is can be
     * read from the statement printed directly underneath it, and a label that
     * guessed would be wrong on the first `WITH` that ends in an `INSERT`.
     */
    fun rows(count: Long?): String? = when (count) {
        null -> null
        1L -> "1 row"
        else -> "${RedisFormat.count(count)} rows"
    }

    /** How an execution ended, as one word for the badge. */
    fun outcome(outcome: ExecutionOutcome): String = when (outcome) {
        ExecutionOutcome.OK -> "ok"
        ExecutionOutcome.ERROR -> "failed"
        ExecutionOutcome.CANCELLED -> "cancelled"
    }

    /**
     * The row's preview of a statement: its shape on one or two lines.
     *
     * Every run of whitespace becomes a single space, so a formatted query with its
     * `FROM` on line four still says what it selects in the first thing the eye lands
     * on. The whole statement is one click away and is shown exactly as it was sent —
     * this is the summary, and a summary that preserved indentation would show three
     * rows of it.
     */
    fun preview(statement: String, limit: Int = 200): String {
        val single = statement.trim().replace(WHITESPACE, " ")
        return if (single.length <= limit) single else single.take(limit - 1) + "…"
    }

    /**
     * The line summarising an entry: when, how long, how many.
     *
     * Assembled from the parts that have answers rather than from a fixed template,
     * for the same reason the Redis dashboard builds itself from the fields `INFO`
     * returned: a cancelled statement has no row count, and " · — rows" is a column
     * of dashes claiming something was measured.
     */
    fun summary(record: ExecutionRecord, zone: ZoneId): String = listOfNotNull(
        time(record.executedAt, zone),
        duration(record.duration),
        rows(record.rowCount),
    ).joinToString(" · ")

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val WHITESPACE = Regex("\\s+")
}
