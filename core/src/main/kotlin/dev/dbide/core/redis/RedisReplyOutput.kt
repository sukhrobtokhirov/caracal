package dev.dbide.core.redis

import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.output.CommandOutput
import java.nio.ByteBuffer

/**
 * Decodes any RESP reply into a [RedisReply], applying [RedisLimits] as it goes.
 *
 * Lettuce ships outputs for every command it knows; this is for the console, which
 * sends commands nobody has typed yet. It follows the same protocol contract
 * `NestedMultiOutput` does — `multi` opens a level, `complete` closes one when the
 * driver's nesting level has passed it, and scalars land in whatever level is
 * currently open — and differs in what it builds and in refusing to build all of it.
 *
 * The budgets are applied *here*, during decode, rather than by trimming a finished
 * object graph. Netty has already received the bytes either way, so this is not about
 * the wire; it is about not turning a 200 MB `CLIENT LIST` into 200 MB of Strings and
 * a paused JVM on the way to discarding most of it. What is refused is refused before
 * it is allocated, and leaves a [RedisReply.Elided] behind so the reader can tell.
 */
internal class RedisReplyOutput(
    codec: RedisCodec<ByteArray, ByteArray>,
    private val limits: RedisLimits,
) : CommandOutput<ByteArray, ByteArray, RedisReply>(codec, RedisReply.Nil) {

    private class Frame(val kind: RedisReply.Items.Kind, val dropping: Boolean, val tooDeep: Boolean) {
        val items = mutableListOf<RedisReply>()
        var truncated = false
    }

    private val stack = ArrayDeque<Frame>()
    private var current: Frame? = null

    /** The whole reply, when it was a bare scalar with no array around it. */
    private var scalar: RedisReply? = null

    /** Mirrors the driver's own nesting counter, which is what [complete] is told about. */
    private var depth = 0

    private var elementsKept = 0
    private var bytesKept = 0L

    /** Whether any budget was reached anywhere. The caller reports this to the user. */
    var truncated: Boolean = false
        private set

    override fun get(): RedisReply = current?.toReply() ?: scalar ?: RedisReply.Nil

    // --- Scalars --------------------------------------------------------------

    override fun set(bytes: ByteBuffer?) = add(bytes.toBulk())

    override fun set(integer: Long) = add(RedisReply.Integer(integer))

    override fun set(value: Double) = add(RedisReply.Decimal(value.toString()))

    override fun set(value: Boolean) = add(RedisReply.Bool(value))

    override fun setSingle(bytes: ByteBuffer?) {
        // A simple string is protocol-level text — `+OK`, `+PONG`, a status line — and
        // is never binary, so it is the one case that decodes without asking.
        add(bytes?.let { RedisReply.Status(decodeString(it)) } ?: RedisReply.Nil)
    }

    override fun setBigNumber(bytes: ByteBuffer?) {
        add(bytes?.let { RedisReply.Decimal(decodeString(it)) } ?: RedisReply.Nil)
    }

    /**
     * An error reply — thrown when it *is* the reply, kept as a value when it is one
     * element of one.
     *
     * The split matters in both directions. An error nested inside an `EXEC` array is
     * not the command's failure: the command succeeded and one of the queued
     * operations did not, so raising it would discard the rest of a reply that arrived
     * intact. A *top-level* error is the command's failure, and letting it through as
     * a value would mean every caller of this output has to remember to inspect the
     * reply for one — including the internal callers that use it to read a page of a
     * stream, where a forgotten check reads as an empty stream.
     */
    override fun setError(bytes: ByteBuffer) = setError(decodeString(bytes))

    override fun setError(error: String) {
        if (current == null) super.setError(error) else add(RedisReply.Failure(error))
    }

    // --- Structure ------------------------------------------------------------

    override fun multi(count: Int) = open(RedisReply.Items.Kind.ARRAY)

    override fun multiArray(count: Int) = open(RedisReply.Items.Kind.ARRAY)

    override fun multiPush(count: Int) = open(RedisReply.Items.Kind.PUSH)

    override fun multiSet(count: Int) = open(RedisReply.Items.Kind.SET)

    override fun multiMap(count: Int) = open(RedisReply.Items.Kind.MAP)

    /**
     * Closes a level once the driver has unwound past it.
     *
     * The condition is the driver's, not a choice: `complete` is called on the way out
     * of every nesting level *and* once at the very end with zero, and the outermost
     * level must survive that last call because it is the reply.
     */
    override fun complete(depth: Int) {
        if (depth > 0 && depth < this.depth) close()
    }

    private fun open(kind: RedisReply.Items.Kind) {
        val parent = current ?: Frame(RedisReply.Items.Kind.ARRAY, dropping = false, tooDeep = false)
        current = parent
        stack.addFirst(parent)
        // The outermost real level is depth 1, so a limit of one keeps a flat array and
        // elides its nested children — which is the reading a caller would expect.
        val tooDeep = stack.size > limits.replyDepth
        if (tooDeep) truncated = true
        current = Frame(kind, dropping = parent.dropping || tooDeep, tooDeep = tooDeep)
        depth++
    }

    private fun close() {
        val finished = current ?: return
        val parent = stack.removeFirstOrNull() ?: return
        current = parent
        depth--
        parent.accept(finished.toReply())
    }

    // --- Budgets --------------------------------------------------------------

    private fun add(reply: RedisReply) {
        val frame = current
        if (frame == null) {
            scalar = reply
            return
        }
        frame.accept(reply)
    }

    private fun Frame.accept(reply: RedisReply) {
        if (dropping) return
        if (elementsKept >= limits.replyElements) {
            // Qualified, and it has to be. Inside an extension on Frame, a bare
            // `truncated` binds to the frame's own flag, so the output's — the one the
            // caller reads to find out whether anything was dropped — silently stays
            // false and a cut reply reports itself as complete.
            this@RedisReplyOutput.truncated = true
            if (!truncated) {
                truncated = true
                items += RedisReply.Elided(Elision.ELEMENTS)
            }
            return
        }
        elementsKept++
        items += reply
    }

    private fun Frame.toReply(): RedisReply = when {
        tooDeep -> RedisReply.Elided(Elision.DEPTH)
        else -> RedisReply.Items(kind = kind, items = items.toList(), truncated = truncated)
    }

    /**
     * A bulk string, kept up to whichever budget bites first.
     *
     * The buffer belongs to Netty and is reused the moment this returns, so what is
     * kept is copied out; nothing here retains a view of it.
     */
    private fun ByteBuffer?.toBulk(): RedisReply {
        if (this == null) return RedisReply.Nil
        val length = remaining()
        val budget = limits.responseBytes - bytesKept
        if (budget <= 0) {
            truncated = true
            return RedisReply.Elided(Elision.BYTES)
        }
        val keep = minOf(length.toLong(), limits.elementBytes.toLong(), budget).toInt()
        val bytes = ByteArray(keep)
        // Absolute reads: the buffer's own position belongs to the driver.
        val start = position()
        for (index in 0 until keep) bytes[index] = get(start + index)
        bytesKept += keep
        if (keep < length) truncated = true
        return RedisReply.Bulk(RedisBytes.window(bytes, total = length, limit = keep))
    }
}
