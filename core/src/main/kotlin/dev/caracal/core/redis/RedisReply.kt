package dev.caracal.core.redis

import kotlin.time.Duration

/** Why part of a reply is not here. */
enum class Elision(val message: String) {
    DEPTH("nested deeper than this build shows"),
    ELEMENTS("more elements than this build keeps"),
    BYTES("larger than this build keeps"),
}

/**
 * A command reply, normalized into the shapes RESP can produce and bounded on the
 * way in.
 *
 * The bounding is the point. A console command is arbitrary — `CLIENT LIST` on a
 * busy server, `XRANGE` over a stream nobody trimmed, a module command that returns
 * a document — and unlike the browser's own reads there is no page size to ask for,
 * because the command decides its own reply. So the budget is applied while the
 * reply is being decoded rather than after: what is dropped is dropped before it
 * becomes an object, and [Elided] is left where it was, so a reader can see that
 * something was there rather than reading a short list as a complete one.
 */
sealed interface RedisReply {

    /**
     * The text of this reply, when it has one.
     *
     * `null` for a reply that is not a string at all, and for a bulk string whose
     * bytes are not text — never a lossy rendering of either.
     */
    val text: String?
        get() = when (this) {
            is Status -> value
            is Bulk -> value.text
            is Integer -> value.toString()
            is Decimal -> value
            is Bool -> value.toString()
            else -> null
        }

    /** RESP's null, in any of its spellings. */
    data object Nil : RedisReply

    data class Integer(val value: Long) : RedisReply

    /**
     * A RESP3 double, kept as the server's own text.
     *
     * Same reason as [ScoredMember.score]: reformatting a double is a chance to move
     * its last digit, on a value someone is reading precisely because they want to
     * know what it is.
     */
    data class Decimal(val value: String) : RedisReply

    data class Bool(val value: Boolean) : RedisReply

    /**
     * A simple string — `+OK`, `+PONG`. Always short, always text.
     *
     * Distinct from [Bulk] only when the connection negotiated RESP3, which is the
     * protocol version that gives the driver a separate hook for one. Over RESP2 a
     * simple string arrives through the same door as a bulk string and lands in
     * [Bulk]. That is a cosmetic difference — `redis-cli` prints `OK` for one and
     * `"OK"` for the other — so a caller that wants the text of a reply should ask
     * for the text rather than match on the case.
     */
    data class Status(val value: String) : RedisReply

    /** A bulk string, which may be binary and may be clipped. */
    data class Bulk(val value: RedisText) : RedisReply

    /**
     * An error reply.
     *
     * A value rather than a thrown failure: `EXEC` and pipelined commands return
     * arrays with errors *inside* them, so an error is a thing a reply can contain
     * and not only a thing a reply can be.
     */
    data class Failure(val message: String) : RedisReply

    /** An array, set, map, or push reply. [kind] is only a label for the viewer. */
    data class Items(
        val kind: Kind,
        val items: List<RedisReply>,
        val truncated: Boolean = false,
    ) : RedisReply {
        enum class Kind { ARRAY, MAP, SET, PUSH }
    }

    /** What the limits stopped this build from keeping, left where it was. */
    data class Elided(val reason: Elision) : RedisReply
}

/**
 * What running one console command produced.
 *
 * [command] is the normalized name — `GET`, `CONFIG SET` — and never the arguments.
 * §3.9 requires the duration and a safe command name to be recorded and the
 * arguments not to be, because an argument is where a password, a token, or a
 * customer's row goes.
 */
data class CommandResult(
    val command: String,
    val reply: RedisReply,
    val duration: Duration,
    /** Whether any budget was reached anywhere in [reply]. */
    val truncated: Boolean,
)
