package dev.caracal.core.redis

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A Redis byte string, and what can honestly be shown of it.
 *
 * Redis has no string type in the sense a database column does: every key, field,
 * member, and value is a byte sequence, and text is a thing a client decides those
 * bytes are. `SET k <jpeg>` is ordinary usage, and so is a UTF-8 sentence — the
 * server does not distinguish them and neither can a reader who is handed a decoded
 * string with the undecodable bytes replaced.
 *
 * So the decision is made once, here, and travels with the value. [Utf8] means the
 * bytes decoded strictly; [Binary] means they did not, and carries hexadecimal
 * instead of a string full of replacement characters that would silently differ from
 * what Redis holds.
 */
sealed interface RedisText {
    /** The true size in bytes, whether or not all of it is here. */
    val byteCount: Int

    /** Whether what is carried is a prefix. The rest stayed on the server. */
    val truncated: Boolean

    /** Bytes that decoded as UTF-8. [value] is exactly those bytes, as text. */
    data class Utf8(
        val value: String,
        override val byteCount: Int,
        override val truncated: Boolean = false,
    ) : RedisText

    /**
     * Bytes that did not decode. [hex] is lowercase and unprefixed, so it can be
     * read against `redis-cli --no-raw` output without translation.
     */
    data class Binary(
        val hex: String,
        override val byteCount: Int,
        override val truncated: Boolean = false,
    ) : RedisText

    /** The text, when there is text. Never a lossy rendering of binary. */
    val text: String? get() = (this as? Utf8)?.value

    /** Whether this is complete, decodable text — the only thing worth parsing as JSON. */
    val isWholeText: Boolean get() = this is Utf8 && !truncated
}

/**
 * Reads Redis bytes into something showable.
 *
 * The one thing worth knowing about the result: **the label describes what was
 * read, not the whole value.** A 40 MB value whose first kilobyte is a UTF-8 header
 * and whose remainder is compressed data is reported as truncated text, because
 * finding out otherwise means reading 40 MB to answer a question about a preview.
 * [RedisText.truncated] is the caller's signal that the classification is about a
 * prefix; a complete value's classification is about all of it.
 */
object RedisBytes {

    /** Decodes at most [limit] bytes of [bytes], deciding text or binary from those. */
    fun of(bytes: ByteArray, limit: Int): RedisText = of(bytes, 0, bytes.size, limit)

    /**
     * As [of], over a window of [bytes]. [length] is what is present; [total] is how
     * large the value is on the server, which for a ranged read is larger.
     */
    fun window(bytes: ByteArray, total: Int, limit: Int): RedisText {
        val kept = of(bytes, 0, bytes.size, limit)
        // The window's own truncation is one reason; being a window is another, and
        // either makes this a prefix.
        val partial = kept.truncated || bytes.size < total
        return when (kept) {
            is RedisText.Utf8 -> kept.copy(byteCount = total, truncated = partial)
            is RedisText.Binary -> kept.copy(byteCount = total, truncated = partial)
        }
    }

    private fun of(bytes: ByteArray, offset: Int, length: Int, limit: Int): RedisText {
        val shown = minOf(length, limit.coerceAtLeast(0))
        val truncated = shown < length
        val text = decode(bytes, offset, shown, allowDanglingCharacter = truncated)
        return if (text != null) {
            RedisText.Utf8(value = text, byteCount = length, truncated = truncated)
        } else {
            RedisText.Binary(hex = hex(bytes, offset, shown), byteCount = length, truncated = truncated)
        }
    }

    /**
     * [length] bytes as UTF-8, or `null` when they are not UTF-8.
     *
     * Strict on purpose. The decoder's default is to replace what it cannot read with
     * `U+FFFD`, which turns "these bytes are not text" into a string that looks like
     * text, compares unequal to what Redis holds, and copies out of the window as a
     * value that was never stored.
     *
     * [allowDanglingCharacter] is for a prefix. Cutting a byte window at a fixed
     * length lands in the middle of a multi-byte character roughly as often as
     * multi-byte characters occur, and that dangling sequence is not evidence the
     * value is binary — it is evidence of where the cut fell. UTF-8 encodes a
     * character in at most four bytes, so dropping up to three recovers the boundary,
     * and a window that still will not decode after that is genuinely not text.
     *
     * The allowance never reaches zero bytes, which is the trap it would otherwise
     * fall into: an empty window decodes successfully, so a window of three bytes of
     * binary would shrink to nothing, "decode", and be reported as empty text. Only a
     * genuinely empty value gets to be empty text.
     */
    private fun decode(bytes: ByteArray, offset: Int, length: Int, allowDanglingCharacter: Boolean): String? {
        if (length == 0) return ""
        val shortest = if (allowDanglingCharacter) maxOf(1, length - 3) else length
        for (end in length downTo shortest) {
            decodeExactly(bytes, offset, end)?.let { return it }
        }
        return null
    }

    private fun decodeExactly(bytes: ByteArray, offset: Int, length: Int): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun hex(bytes: ByteArray, offset: Int, length: Int): String {
        val out = StringBuilder(length * 2)
        for (position in 0 until length) out.append(HEX[bytes[offset + position].toInt() and 0xff])
        return out.toString()
    }

    private val HEX = Array(256) { "%02x".format(it) }
}
