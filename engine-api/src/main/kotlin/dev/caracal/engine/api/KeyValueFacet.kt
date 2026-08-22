package dev.caracal.engine.api

/**
 * The key browser's view of a server.
 *
 * Everything here is bounded and says so in its return type, which is the property
 * that separates a browser from an outage. A key-value server runs commands one at a
 * time on one thread, so an unbounded read is not a slow page for the person who
 * asked — it is a stall for every other client, and the traversal that produces the
 * page is a loop this application drives rather than one the server plans. So a
 * [ScanPage] carries the cursor to continue from *and* [ScanPage.stopped], which
 * says which budget ended it, and an empty page is an ordinary result rather than
 * the end of anything.
 *
 * Declared now rather than in Phase 1 because it now has a caller. Its shape is
 * decided entirely by what the key browser needs to draw, and inventing it before
 * that consumer existed is the failure mode §12 of the spec is about: an interface
 * in the module nobody may change without touching every engine, guessed at.
 *
 * What is deliberately *not* here is anything that writes. The browser reads; the
 * console is where a command that changes something goes, and it goes past
 * [CommandFacet]'s guard on the way.
 */
interface KeyValueFacet {

    /** What a request on this session is allowed to cost. Applied here, not by the caller. */
    val limits: KeyValueLimits

    /**
     * One page of the keyspace, with each key's metadata.
     *
     * [match] is the server's own glob and is passed to it as one, not interpreted
     * here. [count] and [pageSize] are hints: [limits] decides what they actually
     * are, because a caller asking for more than it may have needs a bound rather
     * than an error.
     */
    suspend fun scan(
        cursor: ScanCursor = ScanCursor.START,
        match: String? = null,
        type: KeyType? = null,
        count: Int? = null,
        pageSize: Int? = null,
    ): ScanPage

    /** One key's type, expiry, and size estimate, read fresh. */
    suspend fun metadata(key: KeyRef): KeyMetadata

    /**
     * One page of one value, in the shape its type has.
     *
     * The key's type is re-read before anything else, so a key deleted and recreated
     * as something else reports which viewer to open instead, rather than the bare
     * wrong-type error that says nothing about what to do next.
     */
    suspend fun value(request: ValueRequest): ValuePage
}
