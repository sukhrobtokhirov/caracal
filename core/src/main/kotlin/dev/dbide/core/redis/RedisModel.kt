package dev.dbide.core.redis

import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException

/** The value types this build can browse. Anything else is a key it will not open. */
enum class KeyType(val wire: String) {
    STRING("string"),
    HASH("hash"),
    LIST("list"),
    SET("set"),
    ZSET("zset"),
    STREAM("stream"),
    ;

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }

        /** Redis's own name for a type, or `null` for one this build does not browse. */
        fun of(wire: String?): KeyType? = wire?.let { BY_WIRE[it.trim().lowercase()] }
    }
}

/**
 * A `SCAN` cursor: an opaque token, carried as the base-10 text Redis sent.
 *
 * Not a number, and the reason survives the move off HTTP that §3.2's wording was
 * written for. A Redis cursor is an unsigned 64-bit value, so half its range does
 * not fit in a [Long] as a positive number — a cursor past 2^63 read as a Long comes
 * back negative, and a negative cursor sent back to Redis is a protocol error at the
 * point where a traversal is already half-finished.
 *
 * It is also not an index into anything. Redis builds it by reversing the bits of a
 * bucket number, and the only valid operations on one are "send it back" and
 * "compare it to zero", both of which text does perfectly well.
 */
@JvmInline
value class RedisCursor private constructor(val value: String) {

    /** Redis returns `0` when a traversal has come all the way round. */
    val isComplete: Boolean get() = value == START_VALUE

    override fun toString(): String = value

    companion object {
        private const val START_VALUE = "0"

        /** Where every traversal begins. */
        val START = RedisCursor(START_VALUE)

        /**
         * Validates a cursor that came from outside.
         *
         * Digits only, and inside the unsigned 64-bit range Redis actually uses. The
         * check is here rather than at each call site because a cursor is the one
         * scan parameter that is passed straight through to the server, and passing
         * an arbitrary caller-supplied string into a command argument is how a
         * validation gap becomes a protocol-level surprise.
         */
        fun of(text: String): RedisCursor {
            val trimmed = text.trim()
            val valid = trimmed.isNotEmpty() &&
                trimmed.length <= 20 &&
                trimmed.all { it in '0'..'9' } &&
                trimmed.toULongOrNull() != null
            if (!valid) {
                throw DbException(DbError.InvalidRequest("That is not a scan cursor this server issued."))
            }
            // Normalized, so "007" and "7" are one cursor rather than two that would
            // defeat a caller comparing them.
            return RedisCursor(trimmed.toULong().toString())
        }
    }
}

/**
 * A key name: the bytes Redis holds, and a bounded rendering of them.
 *
 * [bytes] is the only thing ever sent back to the server. §3.4's rule that a command
 * is never reconstructed from a display label is enforced by making that impossible
 * — [display] is lossy by construction (clipped, and hexadecimal when the name is not
 * text), and there is no way back from it to a key.
 *
 * Equality is by content so a scan can deduplicate. Redis returns duplicate keys
 * during a resizing traversal as a matter of course, and identity comparison on
 * arrays would silently never match.
 */
class RedisKey(bytes: ByteArray, limits: RedisLimits = RedisLimits()) {

    /** A private copy: an array handed in or out could be mutated behind this. */
    private val content: ByteArray = bytes.copyOf()

    /** The exact bytes, for sending. A copy, for the same reason. */
    val bytes: ByteArray get() = content.copyOf()

    /** How the name is shown. Clipped, and hexadecimal when the name is not text. */
    val display: RedisText = RedisBytes.of(content, limits.elementBytes)

    /** The name as text when it is text, for a caller building a prefix tree. */
    val text: String? get() = display.text

    private val hash: Int = content.contentHashCode()

    override fun equals(other: Any?): Boolean =
        this === other || (other is RedisKey && content.contentEquals(other.content))

    override fun hashCode(): Int = hash

    /** Never the raw bytes. A key name reaches a log only through this. */
    override fun toString(): String = "RedisKey(${content.size} bytes)"
}

/** How long a key has left. Redis answers `TTL` with two sentinels and a count. */
sealed interface Ttl {
    /** The key exists and will not expire. Redis says `-1`. */
    data object Persistent : Ttl

    /**
     * The key is not there. Redis says `-2`.
     *
     * A normal answer rather than a failure: §3.3's race is that a key seen by `SCAN`
     * can expire before the pipeline behind it runs, which is not an error in either
     * party and must not lose the rest of the page.
     */
    data object Gone : Ttl

    data class ExpiresIn(val seconds: Long) : Ttl

    companion object {
        /** Maps Redis's reply, keeping an unrecognized negative as [Gone] rather than a countdown. */
        fun of(reply: Long): Ttl = when {
            reply >= 0 -> ExpiresIn(reply)
            reply == -1L -> Persistent
            else -> Gone
        }
    }
}

/**
 * `MEMORY USAGE`, which is an estimate and is frequently not available at all.
 *
 * Its own type rather than a nullable [Long] because there are two different reasons
 * for having no number and a user needs them told apart: a key that is gone has no
 * size, and a server that will not answer has not said. §3.3 asks for exactly this —
 * a per-field marker instead of a failed page.
 */
sealed interface MemoryEstimate {
    data class Bytes(val value: Long) : MemoryEstimate

    /** The server declined or does not offer the command. Browsing continues. */
    data object Unavailable : MemoryEstimate

    /** There is no key to measure. */
    data object Absent : MemoryEstimate

    val bytes: Long? get() = (this as? Bytes)?.value
}

/**
 * What is known about one key without reading its value.
 *
 * [type] is `null` exactly when the key is not there. Both of the ways that happens
 * — it expired between the scan and the pipeline, or it never existed and someone
 * asked for it by name — produce the same three answers from Redis, and the same
 * thing needs saying about them.
 */
data class KeyMetadata(
    val key: RedisKey,
    val type: KeyType?,
    val ttl: Ttl,
    val memory: MemoryEstimate,
    /** Set when the key exists but holds a type this build has no viewer for. */
    val unsupportedType: String? = null,
) {
    val exists: Boolean get() = type != null || unsupportedType != null
}

/** Why a scan page stopped where it did. Every one of them is a normal ending. */
enum class ScanStop {
    /** Redis returned cursor `0`. The traversal is over. */
    COMPLETE,

    /** The page filled. There is more, and the cursor says where. */
    PAGE_FULL,

    /** [RedisLimits.scanIterations] ran out before either of the above. */
    ITERATION_BUDGET,

    /** [RedisLimits.scanDuration] ran out before either of the above. */
    TIME_BUDGET,
}

/**
 * One page of a keyspace traversal.
 *
 * [complete] is true only when Redis returned cursor `0`, which §3.2 is emphatic
 * about: an empty [keys] means nothing, because `MATCH` filters on the server and a
 * batch that matched nothing is the ordinary case for a selective pattern.
 *
 * Nothing here is a snapshot. A key created after the traversal passed its bucket is
 * not in the result, and a key present throughout can still be returned twice if the
 * table resized underneath. That is Redis's documented guarantee and this type
 * carries no stronger one.
 */
data class ScanPage(
    val cursor: RedisCursor,
    val keys: List<KeyMetadata>,
    val iterations: Int,
    val stopped: ScanStop,
) {
    val complete: Boolean get() = stopped == ScanStop.COMPLETE
}

/** A hash field and its value. */
data class FieldEntry(val field: RedisText, val value: RedisText)

/**
 * A sorted-set member and its score, as Redis's own text.
 *
 * The score is text and not a [Double] on purpose. Redis stores scores as doubles
 * but formats them for the wire itself, and re-parsing that into a double and
 * re-formatting it for display is two conversions that can each move the last digit
 * — on a value the user is inspecting *because* they care about its exact ordering.
 */
data class ScoredMember(val member: RedisText, val score: String)

/** A list element, carrying the index it actually has. */
data class IndexedElement(val index: Long, val value: RedisText)

/**
 * One stream entry: its ID, and its fields in the order they were written.
 *
 * A list of pairs rather than a map, because a stream entry may repeat a field name
 * and a map would drop one of them.
 */
data class StreamEntry(val id: String, val fields: List<FieldEntry>)

/**
 * One page of a value, in whichever shape the key's type has.
 *
 * [complete] means the whole value has now been seen. [truncated] means this page
 * kept less than it read, because [RedisLimits.responseBytes] ran out — a separate
 * question, and one that can be true of a page that is also the last one.
 */
sealed interface ValuePage {
    val key: RedisKey
    val type: KeyType
    val complete: Boolean
    val truncated: Boolean

    /**
     * A window of a string, in bytes.
     *
     * Byte offsets, not character offsets, because `GETRANGE` takes byte offsets and
     * a Redis string is bytes. [nextOffset] is where a **Show more** continues, and
     * `null` when there is nowhere to continue to.
     *
     * [cappedAt] is set when [RedisLimits.stringMaxBytes] is what ended the read
     * rather than the end of the value — §3.6's hard maximum, which must be *said*
     * rather than silently applied, or the user reads a prefix believing it is a
     * whole value.
     */
    data class Text(
        override val key: RedisKey,
        val content: RedisText,
        val offset: Int,
        val nextOffset: Int?,
        val length: Int,
        override val complete: Boolean,
        val cappedAt: Int? = null,
        override val truncated: Boolean = false,
    ) : ValuePage {
        override val type: KeyType get() = KeyType.STRING
    }

    /** A page of a hash, continued by [cursor]. */
    data class Fields(
        override val key: RedisKey,
        val entries: List<FieldEntry>,
        val cursor: RedisCursor,
        override val complete: Boolean,
        override val truncated: Boolean = false,
    ) : ValuePage {
        override val type: KeyType get() = KeyType.HASH
    }

    /**
     * A page of a set, continued by [cursor].
     *
     * The order is Redis's and means nothing. `SSCAN` walks a hash table, so members
     * arrive in bucket order, which changes when the set is resized.
     */
    data class Members(
        override val key: RedisKey,
        val members: List<RedisText>,
        val cursor: RedisCursor,
        override val complete: Boolean,
        override val truncated: Boolean = false,
    ) : ValuePage {
        override val type: KeyType get() = KeyType.SET
    }

    /**
     * A page of a sorted set, by rank.
     *
     * Offset paging over a structure that reorders itself: a member whose score
     * changes between pages moves, and so can be seen twice or missed. That is
     * inherent to ranked paging and §3.6 asks for it to be explained rather than
     * papered over.
     */
    data class Scored(
        override val key: RedisKey,
        val members: List<ScoredMember>,
        val offset: Long,
        val nextOffset: Long?,
        val total: Long,
        override val complete: Boolean,
        override val truncated: Boolean = false,
    ) : ValuePage {
        override val type: KeyType get() = KeyType.ZSET
    }

    /** A page of a list, by index. Later pages shift when the list is pushed or popped. */
    data class Elements(
        override val key: RedisKey,
        val elements: List<IndexedElement>,
        val offset: Long,
        val nextOffset: Long?,
        val total: Long,
        override val complete: Boolean,
        override val truncated: Boolean = false,
    ) : ValuePage {
        override val type: KeyType get() = KeyType.LIST
    }

    /**
     * A page of a stream, continued from [nextId].
     *
     * [nextId] is the *exclusive* continuation `XRANGE` needs — Redis spells that
     * `(id` — so the entry that ended this page is not repeated at the head of the
     * next one.
     */
    data class Entries(
        override val key: RedisKey,
        val entries: List<StreamEntry>,
        val nextId: String?,
        override val complete: Boolean,
        override val truncated: Boolean = false,
    ) : ValuePage {
        override val type: KeyType get() = KeyType.STREAM
    }
}

/**
 * What page of which value to read.
 *
 * One request type across six viewers, with the continuation fields each of them
 * needs, because the alternative is six overloads whose shared half — the key, the
 * expected type, the page size — has to stay in step by hand. Which fields matter is
 * decided by [type], and the ones that do not apply are ignored rather than rejected:
 * a UI that keeps one request object and edits it as the user pages should not have
 * to clear a stream ID when the selection moves to a hash.
 *
 * [type] is what the caller *believes* the key is, from the last metadata read. It is
 * re-checked against the server before anything is read, and a mismatch is
 * [dev.dbide.core.result.DbError.KeyTypeChanged] rather than a wrong-shaped page.
 */
data class ValueRequest(
    val key: RedisKey,
    val type: KeyType,

    /** Hash and set: where `HSCAN`/`SSCAN` continue from. */
    val cursor: RedisCursor = RedisCursor.START,

    /** String, list, and sorted set: the byte offset or the rank to start at. */
    val offset: Long = 0,

    /** Stream: the ID of the last entry seen, continued past exclusively. */
    val fromId: String? = null,

    /** A hint. [RedisLimits] decides what it actually is. */
    val limit: Int? = null,
)
