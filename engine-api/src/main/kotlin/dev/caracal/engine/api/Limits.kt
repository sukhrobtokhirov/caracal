package dev.caracal.engine.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * What a key-value request is allowed to cost, moved here from
 * `dev.caracal.core.redis` as `KeyValueLimits`.
 *
 * Both ends need it and for different halves. The engine reads the traversal and
 * page bounds, because it is the one issuing the commands; the workspace reads
 * [KeyValueLimits.elementBytes] and [KeyValueLimits.jsonBytes], because previewing a
 * typed command line and deciding whether a value is small enough to parse as JSON
 * are things it does without asking a server. A budget only one of them could see
 * would be two budgets within a release.
 */

/**
 * What a Redis request is allowed to cost.
 *
 * §3.1 asks for one place rather than a constant per call site, and the reason is
 * sharper here than it was for PostgreSQL. A `SELECT` is one statement the server
 * plans and bounds for itself; browsing Redis is a *loop the client drives*, and
 * every bound on it is one this application chose. A missing bound is not a slow
 * page, it is `KEYS *` written the long way — a single-threaded server held for as
 * long as the loop runs, with every other client waiting behind it.
 *
 * So the numbers are deliberately small. They are what a person reads in one screen,
 * not what a machine could fetch.
 *
 * Every field bounds work this application *performs or retains*. Redis has already
 * done the work for a batch it returned, so none of this is a claim about what
 * crossed the network — it is what stops the loop from going round again.
 */
data class KeyValueLimits(
    /**
     * Keys collected before a page is handed back.
     *
     * Also the ceiling on the metadata pipeline: §3.3 issues three commands per key,
     * so this is a hundred keys and three hundred commands, not three hundred keys.
     */
    val keysPerPage: Int = 200,

    /**
     * `SCAN` calls one page may make.
     *
     * The bound that makes `MATCH` safe. A pattern matching nothing still walks the
     * whole keyspace, one bounded batch at a time, and without a stop the loop runs
     * to the end of a database it never had to look at.
     */
    val scanIterations: Int = 20,

    /** Wall-clock across those iterations, whichever budget runs out first. */
    val scanDuration: Duration = 3.seconds,

    /**
     * Redis's own `COUNT` hint per `SCAN`.
     *
     * A hint, and only a hint: Redis may return more or fewer. It is kept modest
     * because `COUNT` is roughly how long the server blocks in one call, and a large
     * one is the latency spike this whole design exists to avoid.
     */
    val scanCount: Int = 100,

    /** Commands in one pipeline flush. A page is split into batches of this. */
    val pipelineBatch: Int = 300,

    /** Entries in one page of a hash, set, sorted set, list, or stream. */
    val entriesPerPage: Int = 100,

    /** Bytes of a string value read per page, through `GETRANGE`. */
    val stringPageBytes: Int = 64 * 1024,

    /**
     * The most of one string this application will ever hold, across every page.
     *
     * §3.6's "Show full still obeys a hard maximum". A value larger than this is not
     * a value a person is reading; it is a file, and this build has no way to hand
     * one over.
     */
    val stringMaxBytes: Int = 4 * 1024 * 1024,

    /** Bytes kept of any one element — a hash field, a set member, a stream value. */
    val elementBytes: Int = 4 * 1024,

    /** Bytes one response may retain in total, across all of its elements. */
    val responseBytes: Int = 8 * 1024 * 1024,

    /** How deep a nested command reply is walked before it is truncated. */
    val replyDepth: Int = 8,

    /** Elements one command reply may retain, counted across every level. */
    val replyElements: Int = 1_000,

    /** How long any single Redis operation may take. */
    val operationTimeout: Duration = 5.seconds,

    /**
     * The largest string this build will try to parse as JSON.
     *
     * §3.7 wants JSON detection on complete, valid UTF-8 only, and detection means
     * *parsing* — an operation whose cost is the size of the input and which is
     * attempted on values a user merely clicked on.
     */
    val jsonBytes: Int = 1 * 1024 * 1024,
) {
    /**
     * A client's requested page size, honoured up to [keysPerPage].
     *
     * §3.1: client values are hints. A request for one key gets one; a request for
     * a million gets [keysPerPage] and no error, because a caller asking for more
     * than it may have does not need a failure, it needs a bound.
     */
    fun keysFor(requested: Int?): Int = requested.coercedInto(keysPerPage)

    /** A client's requested entry count, honoured up to [entriesPerPage]. */
    fun entriesFor(requested: Int?): Int = requested.coercedInto(entriesPerPage)

    /** A client's requested `SCAN COUNT` hint, honoured up to [scanCount]. */
    fun scanCountFor(requested: Int?): Int = requested.coercedInto(scanCount)

    /** A client's requested string window, honoured up to [stringPageBytes]. */
    fun stringBytesFor(requested: Int?): Int = requested.coercedInto(stringPageBytes)

    /** `null` and anything below one become one; anything above [ceiling] becomes [ceiling]. */
    private fun Int?.coercedInto(ceiling: Int): Int =
        if (this == null) ceiling else coerceIn(1, ceiling)
}
