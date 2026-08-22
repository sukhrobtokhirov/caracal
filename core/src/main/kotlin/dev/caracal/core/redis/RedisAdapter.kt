package dev.caracal.core.redis

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.result.asDbError
import dev.caracal.core.text.Redaction
import dev.caracal.engine.api.CommandConsent
import dev.caracal.engine.api.CommandReply
import dev.caracal.engine.api.CommandResult
import dev.caracal.engine.api.FieldEntry
import dev.caracal.engine.api.IndexedElement
import dev.caracal.engine.api.InvalidRequestException
import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.KeyValueLimits
import dev.caracal.engine.api.MemoryEstimate
import dev.caracal.engine.api.RawCommand
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.ScanPage
import dev.caracal.engine.api.ScanStop
import dev.caracal.engine.api.ScoredMember
import dev.caracal.engine.api.ServerInfo
import dev.caracal.engine.api.StreamEntry
import dev.caracal.engine.api.TextValue
import dev.caracal.engine.api.TextValues
import dev.caracal.engine.api.Ttl
import dev.caracal.engine.api.ValuePage
import dev.caracal.engine.api.ValueRequest
import io.lettuce.core.RedisFuture
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor as LettuceCursor
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.protocol.CommandArgs
import io.lettuce.core.protocol.CommandType
import io.lettuce.core.protocol.ProtocolKeyword
import java.time.Duration as JavaDuration
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory

/**
 * Reported when a console command needs an acknowledgement that was not given.
 *
 * Carries the [clearance] so the UI can put the right question on screen — which
 * button, which phrase to type, and what the command actually does. Everything else
 * sees an ordinary [DbException] saying the command was not allowed, which is true.
 */
class CommandConfirmationRequired(val clearance: CommandClearance.Confirm) : DbException(
    DbError.CommandNotAllowed(message = clearance.warning, command = clearance.command),
)

/**
 * Browses one Redis server: bounded traversal, paged values, and a guarded console.
 *
 * The rule the whole of M3 is built around is here rather than stated anywhere else:
 * **nothing in this class issues a command whose cost is the size of the data.** No
 * `KEYS`, no `HGETALL`, no `SMEMBERS`, no `LRANGE key 0 -1`, no `XRANGE` without a
 * `COUNT`. Redis runs commands one at a time on one thread, so a single unbounded
 * read is not a slow response for the person who asked — it is a stall for every
 * other client of that server, which is how a browsing tool causes an outage.
 *
 * The other half of that rule is that the *loop* is bounded too. Replacing `KEYS`
 * with `SCAN` only helps if the client stops scanning: a `SCAN` loop run to
 * completion over a keyspace nothing matches in is `KEYS` with extra round trips.
 * [KeyValueLimits] is where every one of those bounds lives.
 *
 * Unlike [dev.caracal.core.postgres.PostgresAdapter], this class *is* the read-only
 * boundary. PostgreSQL can be told to refuse writes itself; Redis cannot, short of
 * an ACL this application does not administer. So [RedisCommandGuard] is consulted
 * here, on every console command, and a caller that would rather not ask does not
 * get the choice.
 */
class RedisAdapter(
    private val connection: StatefulRedisConnection<ByteArray, ByteArray>,
    private val config: ConnectionConfig,
    private val redaction: Redaction = Redaction.NONE,
    private val limits: KeyValueLimits = KeyValueLimits(),
) {
    private val log = LoggerFactory.getLogger(RedisAdapter::class.java)

    private val async get() = connection.async()

    init {
        connection.timeout = JavaDuration.ofMillis(limits.operationTimeout.inWholeMilliseconds)
    }

    // --- §3.8 INFO ------------------------------------------------------------

    /**
     * The server's own summary, or an empty one marked [ServerInfo.restricted].
     *
     * A refused `INFO` is not a failed workspace. §3.8 is explicit that the key tools
     * keep working when the dashboard cannot be drawn, and a restricted ACL that
     * permits `SCAN` and forbids `INFO` is an ordinary way to hand someone read
     * access — so this is the one call in the class that swallows its failure.
     */
    suspend fun info(): ServerInfo = try {
        RedisInfo.parse(command { async.info().await() })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        log.debug("INFO was not available on this connection")
        ServerInfo(restricted = true)
    }

    // --- §3.2 / §3.3 Key browsing --------------------------------------------

    /**
     * One page of the keyspace, with each key's metadata.
     *
     * [match] is a Redis glob and is passed to the server as one — `user:*`, not a
     * regular expression, and not something this code interprets. [type] filters the
     * page; see [filtered] for where that happens and why.
     *
     * The loop's shape is the part §3.2 cares about. An empty batch is *not* the end:
     * `MATCH` is applied by the server after it has walked a bucket, so a selective
     * pattern produces empty batch after empty batch while the cursor advances
     * normally. Only a returned cursor of `0` ends a traversal, and the budgets end a
     * *page*, which is a different thing — [ScanPage.stopped] says which happened.
     */
    suspend fun scan(
        cursor: ScanCursor = ScanCursor.START,
        match: String? = null,
        type: KeyType? = null,
        count: Int? = null,
        pageSize: Int? = null,
    ): ScanPage = command {
        val target = limits.keysFor(pageSize)
        val args = ScanArgs().limit(limits.scanCountFor(count).toLong())
        // As bytes, because the keyspace is bytes: a pattern with a non-ASCII
        // character has to be encoded the same way the keys it should match were.
        match?.takeIf { it.isNotEmpty() }?.let { args.match(it.toByteArray(Charsets.UTF_8)) }

        // Insertion-ordered and content-keyed: Redis returns duplicates whenever the
        // hash table resizes mid-traversal, and a page that listed the same key twice
        // would look like a data problem rather than a documented property of SCAN.
        val collected = LinkedHashSet<KeyRef>()
        var current = cursor
        var iterations = 0
        val started = TimeSource.Monotonic.markNow()
        var stopped = ScanStop.PAGE_FULL

        while (true) {
            // Checked before issuing, so a budget that has run out costs no round trip
            // and the page that filled is returned whole rather than overshooting by
            // another batch.
            if (collected.size >= target) {
                stopped = ScanStop.PAGE_FULL
                break
            }
            if (iterations >= limits.scanIterations) {
                stopped = ScanStop.ITERATION_BUDGET
                break
            }
            if (started.elapsedNow() >= limits.scanDuration) {
                stopped = ScanStop.TIME_BUDGET
                break
            }

            val batch = async.scan(LettuceCursor.of(current.value), args).await()
            iterations++
            batch.keys.forEach { collected += KeyRef(it, limits) }
            current = ScanCursor.of(batch.cursor)
            if (current.isComplete) {
                stopped = ScanStop.COMPLETE
                break
            }
        }

        ScanPage(
            cursor = current,
            keys = metadata(collected.toList()).filtered(type),
            iterations = iterations,
            stopped = stopped,
        )
    }

    /**
     * The page, narrowed to one type.
     *
     * Filtered here rather than by `SCAN ... TYPE`, and §3.2 sanctions exactly this
     * fallback. Two reasons it is the right one rather than a concession: Lettuce's
     * typed `ScanArgs` has no `TYPE` option, so using it would mean hand-assembling
     * the command; and `SCAN TYPE` does not make the server's work smaller anyway —
     * Redis walks the same buckets and discards the misses itself, so the saving is
     * bandwidth, not scan cost. The metadata pipeline has already read every key's
     * type for the browser's own display, so the filter costs one comparison.
     *
     * A key that vanished between the scan and the pipeline is dropped when a type
     * filter is on — it has no type to match — and kept when one is not, because
     * "this key was here a moment ago" is worth seeing.
     */
    private fun List<KeyMetadata>.filtered(type: KeyType?): List<KeyMetadata> =
        if (type == null) this else filter { it.type == type }

    /**
     * §3.5: everything known about one key, read fresh.
     *
     * Separate from the page so a selected key can be refreshed without re-scanning,
     * and so the value viewer can find out that its key has gone without the browser
     * having to notice first.
     */
    suspend fun metadata(key: KeyRef): KeyMetadata = command { metadata(listOf(key)).single() }

    /**
     * §3.3: `TYPE`, `TTL`, and `MEMORY USAGE` for a page of keys, pipelined.
     *
     * Every future is issued before any is awaited, which is what makes this one round
     * trip rather than three hundred. It is deliberately *not* done by turning off
     * Lettuce's auto-flush: that is a setting on the shared connection, and a second
     * coroutine reading a value while this ran would have its command held in the
     * buffer until this batch flushed. Issuing without awaiting pipelines at the
     * protocol level anyway, which is the property that matters.
     *
     * Batched, because §3.3's ceiling is on commands and not on keys: three per key
     * means a page of a hundred keys is three hundred commands, and a caller asking
     * for a larger page must not turn that into an unbounded write burst.
     *
     * Each future is awaited on its own terms. A key that expired between the `SCAN`
     * and this pipeline answers `none` and `-2` rather than failing, and a
     * `MEMORY USAGE` refused by an ACL fails only its own future — so a restricted
     * user browses with a blank memory column instead of a broken page.
     */
    private suspend fun metadata(keys: List<KeyRef>): List<KeyMetadata> {
        if (keys.isEmpty()) return emptyList()
        val perBatch = maxOf(1, limits.pipelineBatch / COMMANDS_PER_KEY)
        return keys.chunked(perBatch).flatMap { batch ->
            val issued = batch.map { key ->
                val bytes = key.bytes
                Triple(async.type(bytes), async.ttl(bytes), async.memoryUsage(bytes))
            }
            batch.mapIndexed { index, key ->
                val (typeFuture, ttlFuture, memoryFuture) = issued[index]
                val typeName = typeFuture.orNull()?.trim()?.lowercase()
                val present = typeName != null && typeName != MISSING_TYPE
                val type = if (present) KeyType.of(typeName) else null
                KeyMetadata(
                    key = key,
                    type = type,
                    ttl = Ttl.of(ttlFuture.orNull() ?: GONE_TTL),
                    memory = when {
                        !present -> MemoryEstimate.Absent
                        else -> memoryFuture.orNull()?.let { MemoryEstimate.Bytes(it) }
                            ?: MemoryEstimate.Unavailable
                    },
                    unsupportedType = typeName?.takeIf { present && type == null },
                )
            }
        }
    }

    // --- §3.6 Value paging ----------------------------------------------------

    /**
     * One page of a value, in the shape its type has.
     *
     * The type is re-read from the server first, every time. It costs a round trip
     * and it buys the difference between "that key is now a list, here it is" and a
     * bare `WRONGTYPE` — and in Redis a key being deleted and recreated as something
     * else is not an edge case, it is how a cache gets reshaped by the service that
     * owns it.
     */
    suspend fun value(request: ValueRequest): ValuePage = command {
        val actual = currentType(request.key)
        if (actual != request.type) {
            throw DbException(
                DbError.KeyTypeChanged(expected = request.type.wire, actual = actual?.wire),
            )
        }
        when (request.type) {
            KeyType.STRING -> readString(request)
            KeyType.HASH -> readHash(request)
            KeyType.SET -> readSet(request)
            KeyType.ZSET -> readSortedSet(request)
            KeyType.LIST -> readList(request)
            KeyType.STREAM -> readStream(request)
        }
    }

    private suspend fun currentType(key: KeyRef): KeyType? {
        val name = async.type(key.bytes).await()?.trim()?.lowercase()
        return if (name == null || name == MISSING_TYPE) null else KeyType.of(name)
    }

    /**
     * A window of a string, in bytes.
     *
     * `STRLEN` then `GETRANGE`, never `GET`: a `GET` on a 512 MB value transfers 512
     * MB whatever the client intends to display. The offsets are byte offsets because
     * that is what `GETRANGE` takes and because a Redis string has no characters to
     * count — [TextValues] is what decides whether the window happens to be text.
     *
     * [KeyValueLimits.stringMaxBytes] is the hard stop §3.6 asks for, and it is reported
     * through [ValuePage.Text.cappedAt] rather than applied quietly. A value silently
     * cut at four megabytes looks exactly like a value that was four megabytes.
     */
    private suspend fun readString(request: ValueRequest): ValuePage.Text {
        val bytes = request.key.bytes
        val length = async.strlen(bytes).await() ?: 0L
        val ceiling = minOf(length, limits.stringMaxBytes.toLong())
        val offset = request.offset.coerceIn(0, maxOf(0, ceiling))
        val window = limits.stringBytesFor(request.limit).toLong()
        val end = minOf(offset + window, ceiling)
        val cappedAt = limits.stringMaxBytes.takeIf { ceiling < length }

        if (end <= offset) {
            return ValuePage.Text(
                key = request.key,
                content = TextValue.Utf8("", byteCount = length.toInt(), truncated = length > 0),
                offset = offset.toInt(),
                nextOffset = null,
                length = length.toInt(),
                complete = length == 0L,
                cappedAt = cappedAt,
            )
        }

        // GETRANGE's end is inclusive.
        val chunk = async.getrange(bytes, offset, end - 1).await() ?: ByteArray(0)
        return ValuePage.Text(
            key = request.key,
            content = TextValues.window(chunk, total = length.toInt(), limit = chunk.size),
            offset = offset.toInt(),
            // An empty chunk would otherwise hand back the offset it was given: the
            // key deleted between the STRLEN and the GETRANGE leaves "Show more"
            // fetching the same empty page forever.
            nextOffset = (offset + chunk.size).toInt().takeIf { chunk.isNotEmpty() && it < ceiling },
            length = length.toInt(),
            complete = offset + chunk.size >= length,
            cappedAt = cappedAt,
        )
    }

    private suspend fun readHash(request: ValueRequest): ValuePage.Fields {
        val page = async.hscan(
            request.key.bytes,
            LettuceCursor.of(request.cursor.value),
            ScanArgs().limit(limits.entriesFor(request.limit).toLong()),
        ).await()
        val budget = ByteBudget(limits.responseBytes)
        // A LinkedHashMap from Lettuce, so this is the server's own order. A hash
        // cannot hold a duplicate field, so nothing is lost by having gone through one.
        // A sequence, not the collection: `Iterable.takeWhile` is eager, so it ran to
        // completion before `map` had spent a single byte of the budget. Every element
        // was taken whatever the budget said, and the byte bound was never enforced —
        // only the element count was. A sequence interleaves the two, which is what
        // the budget was written to do.
        val entries = page.map.entries.asSequence().takeWhile { budget.isOpen }.map { (field, value) ->
            FieldEntry(field = budget.take(field), value = budget.take(value))
        }.toList()
        return ValuePage.Fields(
            key = request.key,
            entries = entries,
            cursor = ScanCursor.of(page.cursor),
            complete = page.isFinished,
            truncated = budget.exhausted || entries.size < page.map.size,
        )
    }

    private suspend fun readSet(request: ValueRequest): ValuePage.Members {
        val page = async.sscan(
            request.key.bytes,
            LettuceCursor.of(request.cursor.value),
            ScanArgs().limit(limits.entriesFor(request.limit).toLong()),
        ).await()
        val budget = ByteBudget(limits.responseBytes)
        val members = page.values.asSequence().takeWhile { budget.isOpen }.map { budget.take(it) }.toList()
        return ValuePage.Members(
            key = request.key,
            members = members,
            cursor = ScanCursor.of(page.cursor),
            complete = page.isFinished,
            truncated = budget.exhausted || members.size < page.values.size,
        )
    }

    /**
     * A page of a sorted set, by rank.
     *
     * The score arrives as a `Double`, which on this platform is exactly the value
     * Redis stores — a Redis score *is* an IEEE-754 double, and there is no JavaScript
     * between here and the screen to round it. What can still differ is its
     * *spelling*, so [formatScore] writes it the way Redis does rather than the way
     * Java does.
     */
    private suspend fun readSortedSet(request: ValueRequest): ValuePage.Scored {
        val bytes = request.key.bytes
        val total = async.zcard(bytes).await() ?: 0L
        val size = limits.entriesFor(request.limit).toLong()
        val offset = request.offset.coerceIn(0, maxOf(0, total))
        val scored = if (offset >= total) {
            emptyList()
        } else {
            async.zrangeWithScores(bytes, offset, offset + size - 1).await().orEmpty()
        }
        val budget = ByteBudget(limits.responseBytes)
        val members = scored.asSequence().takeWhile { budget.isOpen }.map { entry ->
            ScoredMember(member = budget.take(entry.value), score = formatScore(entry.score))
        }.toList()
        return ValuePage.Scored(
            key = request.key,
            members = members,
            offset = offset,
            nextOffset = (offset + members.size).takeIf { it < total && members.isNotEmpty() },
            total = total,
            complete = offset + members.size >= total,
            truncated = budget.exhausted || members.size < scored.size,
        )
    }

    private suspend fun readList(request: ValueRequest): ValuePage.Elements {
        val bytes = request.key.bytes
        val total = async.llen(bytes).await() ?: 0L
        val size = limits.entriesFor(request.limit).toLong()
        val offset = request.offset.coerceIn(0, maxOf(0, total))
        val values = if (offset >= total) {
            emptyList()
        } else {
            // Inclusive end, and bounded. `LRANGE key 0 -1` is the unbounded read this
            // whole class exists not to issue.
            async.lrange(bytes, offset, offset + size - 1).await().orEmpty()
        }
        val budget = ByteBudget(limits.responseBytes)
        val elements = values.asSequence().takeWhile { budget.isOpen }.mapIndexed { index, value ->
            IndexedElement(index = offset + index, value = budget.take(value))
        }.toList()
        return ValuePage.Elements(
            key = request.key,
            elements = elements,
            offset = offset,
            nextOffset = (offset + elements.size).takeIf { it < total && elements.isNotEmpty() },
            total = total,
            complete = offset + elements.size >= total,
            truncated = budget.exhausted || elements.size < values.size,
        )
    }

    /**
     * A page of a stream, continued past the last entry seen.
     *
     * Dispatched as a raw command rather than through Lettuce's `xrange`, for one
     * reason: `StreamMessage` carries its fields as a `Map`, and a stream entry is
     * allowed to repeat a field name. Reading it as a map silently keeps the last of
     * each — on the one data structure in Redis whose whole purpose is to record
     * exactly what was appended.
     *
     * The continuation is exclusive. `XRANGE` takes an inclusive start, so resuming
     * from the last ID would repeat that entry at the head of every page; Redis spells
     * the exclusive form with a leading parenthesis, and that is what is sent.
     */
    private suspend fun readStream(request: ValueRequest): ValuePage.Entries {
        val size = limits.entriesFor(request.limit)
        val start = request.fromId?.let { "($it" } ?: "-"
        val reply = dispatch(
            keyword = CommandType.XRANGE,
            arguments = listOf(request.key.bytes) +
                listOf(start, "+", "COUNT", size.toString()).map { it.toByteArray(Charsets.UTF_8) },
        )
        val entries = (reply as? CommandReply.Items)?.items.orEmpty().mapNotNull { it.asStreamEntry() }
        // A short page means the stream ended inside it — unless the reply budget cut
        // it short, which is a different thing entirely. Deciding on the count alone
        // read a budget-truncated page as the end of the stream: it reported
        // `complete` with no continuation id, and every entry past the cut became
        // unreachable. One entry costs `2 * fields + 3` reply elements, so a stream
        // with a handful of fields per entry reaches the element budget inside a
        // normal-sized page rather than in some pathological case.
        val ended = entries.size < size && !reply.wasTruncated()
        return ValuePage.Entries(
            key = request.key,
            entries = entries,
            nextId = entries.lastOrNull()?.id.takeIf { !ended },
            complete = ended,
            truncated = reply.wasTruncated(),
        )
    }

    /** `[id, [field, value, ...]]`, which is `XRANGE`'s shape in both RESP versions. */
    private fun CommandReply.asStreamEntry(): StreamEntry? {
        val parts = (this as? CommandReply.Items)?.items ?: return null
        val id = parts.getOrNull(0)?.asText()?.text ?: return null
        val flat = (parts.getOrNull(1) as? CommandReply.Items)?.items.orEmpty()
        val fields = flat.chunked(2).mapNotNull { pair ->
            val field = pair.getOrNull(0)?.asText() ?: return@mapNotNull null
            FieldEntry(field = field, value = pair.getOrNull(1)?.asText() ?: EMPTY_TEXT)
        }
        return StreamEntry(id = id, fields = fields)
    }

    private fun CommandReply.asText(): TextValue? = when (this) {
        is CommandReply.Bulk -> value
        is CommandReply.Status -> TextValue.Utf8(value, value.toByteArray(Charsets.UTF_8).size)
        is CommandReply.Integer -> TextValue.Utf8(value.toString(), value.toString().length)
        else -> null
    }

    private fun CommandReply.wasTruncated(): Boolean = when (this) {
        is CommandReply.Elided -> true
        is CommandReply.Items -> truncated || items.any { it.wasTruncated() }
        else -> false
    }

    // --- §3.9 / §3.10 Console -------------------------------------------------

    /**
     * Runs a console command, once the guard is satisfied.
     *
     * [consent] is what the user has agreed to for *this* command. A command needing
     * an acknowledgement that has none throws [CommandConfirmationRequired] carrying
     * the question to ask; there is no way to opt out of asking, because the check is
     * here and not in the caller.
     *
     * What is logged is the duration and [RawCommand.label], and nothing else. §3.9
     * requires the arguments to stay out of the log at normal verbosity, and the
     * reason is one line long: `AUTH`, `CONFIG SET requirepass`, and `SET session:…`
     * are all commands whose arguments are the secret.
     */
    suspend fun execute(
        command: RawCommand,
        consent: CommandConsent = CommandConsent.None,
    ): CommandResult {
        when (val clearance = RedisCommandGuard.clearanceFor(command, config)) {
            CommandClearance.Granted -> Unit
            is CommandClearance.Refused -> throw DbException(clearance.error)
            is CommandClearance.Confirm -> {
                val typed = (consent as? CommandConsent.Given)?.typed
                if (typed == null || !clearance.satisfiedBy(typed)) {
                    throw CommandConfirmationRequired(clearance)
                }
            }
        }

        val started = TimeSource.Monotonic.markNow()
        val output = RedisReplyOutput(ByteArrayCodec.INSTANCE, limits)
        val reply = command {
            dispatch(RawKeyword(command.arguments.first(), command.label), command.arguments.drop(1), output)
        }
        val elapsed = started.elapsedNow()
        log.debug("redis command {} in {}", command.label, elapsed)
        return CommandResult(
            command = command.label,
            reply = reply,
            duration = elapsed,
            truncated = output.truncated,
        )
    }

    /** Runs a command built here rather than typed, so no guard and no consent. */
    private suspend fun dispatch(keyword: ProtocolKeyword, arguments: List<ByteArray>): CommandReply =
        dispatch(keyword, arguments, RedisReplyOutput(ByteArrayCodec.INSTANCE, limits))

    private suspend fun dispatch(
        keyword: ProtocolKeyword,
        arguments: List<ByteArray>,
        output: RedisReplyOutput,
    ): CommandReply {
        val args = CommandArgs(ByteArrayCodec.INSTANCE)
        arguments.forEach { args.add(it) }
        return async.dispatch(keyword, output, args).await()
    }

    // --- Plumbing -------------------------------------------------------------

    /** Converts any Lettuce failure into a classified, scrubbed [DbException]. */
    private suspend fun <T> command(body: suspend () -> T): T = try {
        body()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: DbException) {
        throw failure
    } catch (failure: InvalidRequestException) {
        // A malformed request, raised by the SPI's own types before anything was
        // sent — a cursor Redis did not issue, most often. Classified here rather
        // than left to fall through, because the fall-through arm asks Lettuce what
        // went wrong on the wire and nothing went to the wire at all: it would
        // report a bad cursor as a broken connection and send the user to reconnect.
        throw DbException(failure.asDbError(), failure)
    } catch (failure: Throwable) {
        throw DbException(RedisErrors.classify(failure, redaction), failure)
    }

    /**
     * This future's value, or `null` when it failed.
     *
     * Only for the metadata pipeline, where §3.3 requires one command's failure not to
     * take the page with it. Everywhere else a failure is the answer and is thrown.
     */
    private suspend fun <T> RedisFuture<T>.orNull(): T? = try {
        await()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        null
    }

    /**
     * How many bytes one response may still keep.
     *
     * The bound §3.6 asks for beyond the entry count, and the case that needs it is
     * ordinary: a hundred hash fields is a small page, and a hundred hash fields each
     * holding a serialized document is not. Per-element clipping alone would not catch
     * it, because a hundred four-kilobyte previews is still four hundred kilobytes
     * retained per page and there can be a page open per tab.
     */
    private inner class ByteBudget(private val ceiling: Int) {
        private var spent = 0
        var exhausted = false
            private set

        val isOpen: Boolean get() = !exhausted

        fun take(bytes: ByteArray): TextValue {
            val text = TextValues.of(bytes, limits.elementBytes)
            spent += minOf(bytes.size, limits.elementBytes)
            if (spent >= ceiling) exhausted = true
            return text
        }
    }

    /**
     * An arbitrary command name, sent as the exact bytes the user gave.
     *
     * Not the normalized upper-case name. The normalization exists so the *guard*
     * cannot be fooled by spacing or case, and it deliberately produces more tokens
     * than were typed — sending that back to Redis would mean running a command the
     * user did not write. This sends argument zero verbatim, so a malformed one is
     * rejected by Redis as the malformed command it is.
     */
    private class RawKeyword(private val raw: ByteArray, private val label: String) : ProtocolKeyword {
        override fun getBytes(): ByteArray = raw

        override fun toString(): String = label
    }

    private companion object {
        /** `TYPE`, `TTL`, and `MEMORY USAGE`. */
        const val COMMANDS_PER_KEY = 3

        /** What `TYPE` answers for a key that is not there. */
        const val MISSING_TYPE = "none"

        /** What `TTL` answers for the same. */
        const val GONE_TTL = -2L

        val EMPTY_TEXT = TextValue.Utf8("", byteCount = 0)

        /**
         * A score, spelled the way Redis spells it.
         *
         * Redis writes a whole-numbered score without a decimal point and its
         * infinities as `inf` and `-inf`; Java writes `3.0` and `Infinity`. The value
         * is identical either way — this is only about not showing a user a number they
         * would not recognize from `redis-cli`.
         */
        fun formatScore(score: Double): String = when {
            score.isNaN() -> "nan"
            score == Double.POSITIVE_INFINITY -> "inf"
            score == Double.NEGATIVE_INFINITY -> "-inf"
            score == Math.rint(score) && Math.abs(score) < 1e17 -> score.toLong().toString()
            else -> score.toString()
        }
    }
}
