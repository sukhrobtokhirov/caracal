package dev.caracal.engine.api

/**
 * What a statement or command looks like it will do.
 *
 * "Looks like" is the honest verb, and the split it belongs to is §7's: **classifying
 * is per engine, deciding is core's.** Only the PostgreSQL driver knows that
 * `SELECT ... FOR SHARE` takes locks a read-only transaction will refuse; only the
 * Redis driver knows that `FLUSHALL` empties every database on the server. Neither
 * knows whether this connection is production, and neither should.
 *
 * [UNKNOWN] is the arm that carries the safety property, and it has two halves that
 * are easy to state and easy to lose:
 *
 * 1. A classifier that cannot parse its input returns [UNKNOWN]. Never [READ_ONLY].
 * 2. Whoever consumes one treats [UNKNOWN] as at least [WRITE].
 *
 * A false [WRITE] costs one confirmation dialog. A false [READ_ONLY] costs a
 * production table.
 */
enum class WriteIntent {
    READ_ONLY,
    WRITE,
    DDL,

    /** Destroys broad data rather than rows — `DROP DATABASE`, `FLUSHALL`. */
    DESTRUCTIVE,

    /** Affects every other client of the server — `KEYS`, `SAVE`, a blocking script. */
    SERVER_AFFECTING,

    /** Changes this session and nothing else — `BEGIN`, `SET`, `DISCARD`, `SELECT` on a cursor. */
    CONNECTION_AFFECTING,

    UNKNOWN,
    ;

    /** Whether this intent modifies anything the next reader would see. */
    val modifies: Boolean
        get() = this != READ_ONLY && this != CONNECTION_AFFECTING
}

/**
 * Reads one statement or command and says what it looks like it will do.
 *
 * Deliberately not a shared implementation. §12 of the spec lists the Redis command
 * guard among the things that must not become a generic dangerous-statement
 * classifier, and the reason is visible in the two that exist: one is a SQL keyword
 * scanner that lexes strings and comments away first, the other is an allowlist of
 * command names, because Redis gains commands with every release and with every
 * module loaded. A denylist of Redis writes is wrong the first time somebody
 * installs RedisJSON, and wrong in the direction that lets a write through.
 */
fun interface IntentClassifier {
    fun classify(statement: String): WriteIntent
}
