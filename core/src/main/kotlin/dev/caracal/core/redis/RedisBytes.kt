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
        // Being a window is itself a reason to allow a dangling character. Deciding it
        // from `shown < length` alone got this backwards on the only paths that
        // produce windows: both callers pass a limit equal to the window's own size,
        // so the internal comparison is always false and the decode was strict. A
        // GETRANGE that cut a 200 KB JSON document mid-character then failed to
        // decode and the whole document was shown as hexadecimal.
        val kept = of(bytes, 0, bytes.size, limit, allowDangling = bytes.size < total)
        // The window's own truncation is one reason; being a window is another, and
        // either makes this a prefix.
        val partial = kept.truncated || bytes.size < total
        return when (kept) {
            is RedisText.Utf8 -> kept.copy(byteCount = total, truncated = partial)
            is RedisText.Binary -> kept.copy(byteCount = total, truncated = partial)
        }
    }

    private fun of(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        limit: Int,
        allowDangling: Boolean = false,
    ): RedisText {
        val shown = minOf(length, limit.coerceAtLeast(0))
        val truncated = shown < length
        val text = decode(bytes, offset, shown, allowDanglingCharacter = truncated || allowDangling)
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
     * value is binary — it is evidence of where the cut fell.
     *
     * What is dropped is exactly the incomplete sequence, found by [danglingBytes],
     * and never anything else. Retrying successively shorter windows instead — the
     * obvious version — decides that binary is text as soon as some prefix of it
     * happens to decode: a two-byte window of `00 ff` shrinks to `00`, which is a
     * perfectly good NUL, and a value that is not text at all is reported as text
     * with its last byte quietly missing. Trimming a sequence that is genuinely
     * unfinished cannot do that, because a byte that cannot begin a UTF-8 character
     * is not an unfinished one.
     *
     * A window that is nothing but a dangling sequence stays binary. An empty window
     * decodes successfully, so trimming to nothing would report three bytes of binary
     * as empty text; only a genuinely empty value gets to be empty text.
     */
    private fun decode(bytes: ByteArray, offset: Int, length: Int, allowDanglingCharacter: Boolean): String? {
        if (length == 0) return ""
        val dangling = if (allowDanglingCharacter) danglingBytes(bytes, offset, length) else 0
        val end = length - dangling
        if (end == 0) return null
        return decodeExactly(bytes, offset, end)
    }

    /**
     * How many trailing bytes begin a UTF-8 character the window cut short, which is
     * zero unless they do.
     *
     * At most three: a UTF-8 character is at most four bytes, so a cut one leaves a
     * lead byte and at most two continuations. Three continuation bytes in a row with
     * no lead byte within reach cannot be a cut character, and neither can a byte
     * that is not a legal lead byte at all.
     */
    private fun danglingBytes(bytes: ByteArray, offset: Int, length: Int): Int {
        var back = 0
        while (back < 3 && back < length) {
            val byte = bytes[offset + length - 1 - back].toInt() and 0xff
            if (byte and 0xC0 == 0x80) {
                back++
                continue
            }
            val needed = when {
                byte and 0x80 == 0x00 -> 1
                byte and 0xE0 == 0xC0 -> 2
                byte and 0xF0 == 0xE0 -> 3
                byte and 0xF8 == 0xF0 -> 4
                else -> return 0
            }
            val present = back + 1
            return if (needed > present) present else 0
        }
        return 0
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
