package dev.dbide.core.history

import dev.dbide.core.connections.ConnectionId
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
