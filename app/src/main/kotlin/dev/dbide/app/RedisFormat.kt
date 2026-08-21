package dev.dbide.app

import dev.dbide.core.redis.MemoryEstimate
import dev.dbide.core.redis.RedisText
import dev.dbide.core.redis.Ttl
import kotlin.time.Duration

/**
 * How Redis's own answers are written on screen.
 *
 * Here rather than in the composables because the same three questions — how long has
 * this key got, how big is it, what does this byte string look like — are asked by the
 * browser row, the value header, and the console transcript, and three answers to one
 * question is how a screen stops reading as one application.
 *
 * The rule running through all of it: **nothing here invents precision.** A memory
 * estimate that is unavailable says so rather than showing a zero, a TTL of `-2` is
 * the key being gone rather than a countdown that has run out, and a byte string that
 * is not text is shown as hexadecimal rather than as a string full of replacement
 * characters that would differ from what Redis holds.
 */
object RedisFormat {

    /** How long a key has left, or what it is instead of a countdown. */
    fun ttl(ttl: Ttl): String = when (ttl) {
        Ttl.Persistent -> "no expiry"
        Ttl.Gone -> "gone"
        is Ttl.ExpiresIn -> "expires in ${seconds(ttl.seconds)}"
    }

    /** The same, short enough for a list row. */
    fun ttlShort(ttl: Ttl): String? = when (ttl) {
        // The ordinary case, and the one that would be noise on every row of a list.
        Ttl.Persistent -> null
        Ttl.Gone -> "gone"
        is Ttl.ExpiresIn -> seconds(ttl.seconds)
    }

    /**
     * A size estimate, or why there is not one.
     *
     * The two ways of having no number are told apart deliberately: §3.3 asks for a
     * per-field marker, and "the server will not say" and "there is no key to measure"
     * send a reader to different places.
     */
    fun memory(estimate: MemoryEstimate): String = when (estimate) {
        is MemoryEstimate.Bytes -> bytes(estimate.value)
        MemoryEstimate.Unavailable -> "size unavailable"
        MemoryEstimate.Absent -> "—"
    }

    /** The same for a list row: the number, or nothing at all. */
    fun memoryShort(estimate: MemoryEstimate): String? = estimate.bytes?.let(::bytes)

    /**
     * A byte count, in the units a person reads.
     *
     * Powers of 1024 with the SI-adjacent spelling Redis's own `INFO` uses, so a
     * number here can be compared with one from `redis-cli` without conversion.
     */
    fun bytes(count: Long): String {
        if (count < 1024) return "$count B"
        var value = count.toDouble()
        var unit = 0
        while (value >= 1024 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        val rounded = if (value >= 100) "%.0f".format(value) else "%.1f".format(value)
        return "$rounded ${UNITS[unit]}"
    }

    fun bytes(count: Int): String = bytes(count.toLong())

    /** A count, grouped by thousands so seven digits can be read at a glance. */
    fun count(value: Long): String = value.toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

    /** How long a command took. Sub-millisecond is the ordinary case for Redis. */
    fun duration(duration: Duration): String {
        val millis = duration.inWholeMicroseconds / 1000.0
        return when {
            millis < 1 -> "%.2f ms".format(millis)
            millis < 100 -> "%.1f ms".format(millis)
            else -> "${duration.inWholeMilliseconds} ms"
        }
    }

    /** An uptime, at the resolution anyone actually reads one: days, then hours. */
    fun uptime(duration: Duration): String = seconds(duration.inWholeSeconds)

    /**
     * What a byte string looks like in a list.
     *
     * Never a lossy decode: a value that is not text is hexadecimal, prefixed so it
     * cannot be mistaken for a name that happens to be digits.
     */
    fun text(text: RedisText): String = when (text) {
        is RedisText.Utf8 -> text.value
        is RedisText.Binary -> "0x${text.hex}"
    }

    /** The one-line form: a single line of it, clipped, for a row that has one line. */
    fun oneLine(text: RedisText, limit: Int = 120): String {
        val whole = text(text).replace(NEWLINES, "⏎")
        return if (whole.length <= limit) whole else whole.take(limit - 1) + "…"
    }

    /** A duration in seconds, written the way a person says it. */
    private fun seconds(total: Long): String {
        if (total < 60) return "${total}s"
        val minutes = total / 60
        if (minutes < 60) return "${minutes}m ${total % 60}s"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ${minutes % 60}m"
        return "${hours / 24}d ${hours % 24}h"
    }

    private val UNITS = listOf("B", "kB", "MB", "GB", "TB")

    private val NEWLINES = Regex("\\r\\n|\\r|\\n")
}
