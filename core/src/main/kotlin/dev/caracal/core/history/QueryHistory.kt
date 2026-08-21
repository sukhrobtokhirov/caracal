package dev.caracal.core.history

import dev.caracal.core.connections.ConnectionId
import java.time.Instant
import kotlin.time.Duration

/**
 * How an attempted execution ended.
 *
 * [stored] is the text written to the database, spelled out rather than derived
 * from the constant's name. A Kotlin rename is a refactor; it must not also be a
 * silent migration that orphans every row written under the old spelling.
 */
enum class ExecutionOutcome(val stored: String) {
    OK("ok"),

    /** The server refused it, or the connection failed. Distinct from cancellation. */
    ERROR("error"),

    /** The user stopped it, or whatever owned it went away. Nothing failed. */
    CANCELLED("cancelled"),
    ;

    companion object {
        /** The outcome [stored] names, or `null` for a value this build does not know. */
        fun of(stored: String): ExecutionOutcome? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * One attempted execution, as history keeps it.
 *
 * Recorded whatever became of the statement, which is the point: a query that
 * failed at 2am is exactly the one someone comes back looking for, and a history
 * of successes only would have thrown it away.
 *
 * [statement] is the submitted text, byte for byte. It is the one genuinely
 * sensitive field here — a `WHERE email = '...'` is a record of a person as much
 * as of a query — so it lives in the same owner-only database as the sealed
 * credentials and never reaches a log. [error] is a message that has already been
 * through `Throwable.toFailure()`, so it carries no host, user name, or driver
 * text; the caller does that conversion, and this type simply refuses to be given
 * anything else.
 *
 * [rowCount] is rows returned for a statement that produced a result set, and rows
 * affected for one that did not. Which of the two it is can be read from the
 * statement, and storing a flag to say so would be storing the classifier's
 * opinion alongside the fact.
 *
 * [id] is the database's own, and is `null` for a record that has not been written
 * yet.
 */
data class ExecutionRecord(
    val connectionId: ConnectionId,
    val statement: String,
    val outcome: ExecutionOutcome,
    val executedAt: Instant,
    val duration: Duration? = null,
    val rowCount: Long? = null,
    val error: String? = null,
    val id: Long? = null,
)

/**
 * How much history is kept, per connection.
 *
 * Per connection rather than in total, because the alternative is that an
 * afternoon spent in a staging database quietly evicts every production query you
 * ran last week. A cap of a thousand is a few hundred kilobytes of SQL and more
 * scrollback than anyone reads; §2.11 asks for a cap and explicitly does not ask
 * for a policy screen to configure it, so this is a constructor parameter and not
 * a setting.
 */
const val DEFAULT_HISTORY_RETENTION: Int = 1_000

/**
 * A place in the history ordering, handed back to ask for the page after it.
 *
 * Opaque on purpose. Its one field is `internal`, so `:app` can carry a cursor from
 * a page to the next request and cannot build one — which is the whole point of a
 * cursor rather than an offset. A caller that could construct one would be a caller
 * writing a predicate against a table it is not supposed to know the shape of, and
 * the first schema change would break it silently.
 *
 * Offsets were the alternative and they are wrong here for the ordinary reason: a
 * query executed while the panel is open shifts every row down by one, so page two
 * of an offset pagination repeats the last row of page one. History gains rows at
 * exactly the end the panel starts from.
 */
class HistoryCursor internal constructor(internal val id: Long) {
    /**
     * Two cursors are the same when they name the same place.
     *
     * Equality and nothing else. A caller can ask "is this the position that page
     * ended at" and cannot ask which of two positions is further back, because
     * ordering is a property of the store's key rather than of a token the caller
     * happens to be holding.
     */
    override fun equals(other: Any?): Boolean = other is HistoryCursor && other.id == id

    override fun hashCode(): Int = id.hashCode()

    /** Deliberately says nothing. A cursor in a log line is a row identifier in a log line. */
    override fun toString(): String = "HistoryCursor(…)"
}

/**
 * Where this entry sits in the ordering, or `null` for one that has not been written
 * yet.
 *
 * The only way to obtain a cursor other than from a [HistoryPage] — and it still is
 * not a way to *invent* one: a caller can name a position it has already been handed
 * a row for, and nothing else.
 */
fun ExecutionRecord.cursor(): HistoryCursor? = id?.let(::HistoryCursor)

/**
 * What to read back: whose, which endings, and where to carry on from.
 *
 * Every field is optional because every combination is a real question. All
 * connections and all outcomes is "what have I been doing"; one connection and
 * [ExecutionOutcome.ERROR] is "what did I break on staging this morning".
 */
data class HistoryQuery(
    /** One connection, or every one of them. */
    val connectionId: ConnectionId? = null,

    /** One ending, or all three. */
    val outcome: ExecutionOutcome? = null,

    /** Where the previous page stopped. `null` starts at the newest entry. */
    val olderThan: HistoryCursor? = null,

    /** How many rows to return. Clamped to [MAX_HISTORY_PAGE] by the store. */
    val limit: Int = HISTORY_PAGE,
)

/**
 * One page of history, and where the next one starts.
 *
 * [next] is non-null only when there is provably at least one more row — the store
 * asks for one row more than it was going to return and throws it away. "There may
 * be more" would produce a Show more button that, pressed at the end of a
 * connection's history, does nothing and explains nothing.
 */
data class HistoryPage(val items: List<ExecutionRecord>, val next: HistoryCursor? = null) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * How much history a deletion is being asked to remove.
 *
 * A sealed pair rather than a nullable connection id, because the difference between
 * the two is the difference between forgetting an afternoon and forgetting a year,
 * and a `null` that quietly means "all of it" is how the second one happens by
 * accident.
 */
sealed interface HistoryScope {
    /** Every connection's history. */
    data object Everything : HistoryScope

    /** One connection's history. The connection itself is not touched. */
    data class OneConnection(val id: ConnectionId) : HistoryScope
}

/** The page size a panel asks for when it has no reason to ask for another. */
const val HISTORY_PAGE: Int = 50

/**
 * The largest page the store will return, whatever it is asked for.
 *
 * A limit that the caller chooses without a ceiling is not a limit. History is
 * bounded per connection at [DEFAULT_HISTORY_RETENTION], so an unclamped request
 * could read a thousand statements — every one of them a string the panel then
 * holds — in a single call.
 */
const val MAX_HISTORY_PAGE: Int = 200
