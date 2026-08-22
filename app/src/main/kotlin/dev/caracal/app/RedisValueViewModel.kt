package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.engine.api.FieldEntry
import dev.caracal.engine.api.IndexedElement
import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.KeyValueLimits
import dev.caracal.engine.api.TextValue
import dev.caracal.engine.api.ScoredMember
import dev.caracal.engine.api.StreamEntry
import dev.caracal.engine.api.ValuePage
import dev.caracal.engine.api.ValueRequest
import dev.caracal.core.result.DbError
import dev.caracal.core.result.Failure
import dev.caracal.core.result.asDbError
import dev.caracal.core.result.toFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Every page of a value read so far, joined into the one list its viewer draws.
 *
 * The accumulation is the whole point of the type. `:core` hands back one bounded
 * page at a time and remembers nothing between them, which is what keeps a 400 000
 * member set from arriving in a single reply; what a person reading that set needs is
 * for **Show more** to add to what they were already looking at rather than replace
 * it. So the continuation token of the last page travels with the joined-up list, and
 * nothing here re-reads a page it already has.
 */
sealed interface LoadedValue {
    val type: KeyType

    /** Whether the whole value has now been read. */
    val complete: Boolean

    /** Whether any page kept less than it read, because a byte budget ran out. */
    val truncated: Boolean

    /** Whether **Show more** would read anything. */
    val hasMore: Boolean

    /** How many items are on screen, for the viewer's own count. */
    val loaded: Int

    /**
     * A string, in the byte windows it was read in.
     *
     * The windows are kept separately rather than concatenated as they arrive because
     * a window that lands mid-character does not decode, and `:core` reports that
     * honestly by handing back [TextValue.Binary] for that page alone. Joining the
     * text of a run of windows is therefore only valid when every one of them decoded,
     * and [text] is the single place that check is made.
     */
    data class Text(
        val windows: List<TextValue>,
        /** The value's size on the server, whether or not all of it is here. */
        val length: Int,
        /** How much of it has been read. */
        val loadedBytes: Int,
        val nextOffset: Int?,
        /** Set when [KeyValueLimits.stringMaxBytes] is what ends the read, not the value. */
        val cappedAt: Int?,
        override val complete: Boolean,
        override val truncated: Boolean,
    ) : LoadedValue {
        override val type: KeyType get() = KeyType.STRING
        override val hasMore: Boolean get() = nextOffset != null
        override val loaded: Int get() = loadedBytes

        /** Whether any window failed to decode, which makes the whole value binary here. */
        val binary: Boolean by lazy { windows.any { it !is TextValue.Utf8 } }

        /**
         * The text read so far, or `null` when some of it is not text.
         *
         * Computed once, not on every read. These were `get()` properties, and a
         * composable that reads one is not skippable — the value holds a `List`, so
         * Compose treats it as unstable and re-runs the body on every recomposition
         * of anything above it. At the 4 MB the string limit allows, one read of
         * [hex] is an eight-million-character string built from scratch, and the
         * window froze for seconds per frame while paging through a large value.
         * The same reasoning already applies to `json` a few lines down.
         */
        val text: String? by lazy {
            if (binary) null else windows.joinToString("") { (it as TextValue.Utf8).value }
        }

        /** The bytes read so far, as hexadecimal, for a value that is not text. */
        val hex: String by lazy {
            windows.joinToString("") {
                when (it) {
                    is TextValue.Binary -> it.hex
                    is TextValue.Utf8 -> it.value.toByteArray(Charsets.UTF_8)
                        .joinToString("") { byte -> "%02x".format(byte) }
                }
            }
        }
    }

    /** A hash, continued by [cursor]. */
    data class Fields(
        val entries: List<FieldEntry>,
        val cursor: ScanCursor,
        override val complete: Boolean,
        override val truncated: Boolean,
    ) : LoadedValue {
        override val type: KeyType get() = KeyType.HASH
        override val hasMore: Boolean get() = !complete
        override val loaded: Int get() = entries.size
    }

    /** A set, continued by [cursor]. The order is Redis's and means nothing. */
    data class Members(
        val members: List<TextValue>,
        val cursor: ScanCursor,
        override val complete: Boolean,
        override val truncated: Boolean,
    ) : LoadedValue {
        override val type: KeyType get() = KeyType.SET
        override val hasMore: Boolean get() = !complete
        override val loaded: Int get() = members.size
    }

    /** A sorted set, by rank. */
    data class Scored(
        val members: List<ScoredMember>,
        val nextOffset: Long?,
        val total: Long,
        override val complete: Boolean,
        override val truncated: Boolean,
    ) : LoadedValue {
        override val type: KeyType get() = KeyType.ZSET
        override val hasMore: Boolean get() = nextOffset != null
        override val loaded: Int get() = members.size
    }

    /** A list, by index. */
    data class Elements(
        val elements: List<IndexedElement>,
        val nextOffset: Long?,
        val total: Long,
        override val complete: Boolean,
        override val truncated: Boolean,
    ) : LoadedValue {
        override val type: KeyType get() = KeyType.LIST
        override val hasMore: Boolean get() = nextOffset != null
        override val loaded: Int get() = elements.size
    }

    /** A stream, continued exclusively past [nextId]. */
    data class Entries(
        val entries: List<StreamEntry>,
        val nextId: String?,
        override val complete: Boolean,
        override val truncated: Boolean,
    ) : LoadedValue {
        override val type: KeyType get() = KeyType.STREAM
        override val hasMore: Boolean get() = nextId != null
        override val loaded: Int get() = entries.size
    }
}

/** What the value pane has to show for the selected key. */
sealed interface ValueState {
    /** No key is selected. */
    data object Idle : ValueState

    /** The key's metadata is being read, or its first page is. */
    data object Loading : ValueState

    /**
     * The key is there and [value] is what has been read of it.
     *
     * [paging] is a page arriving *behind* what is already on screen, which is drawn
     * as a spinner on the **Show more** button rather than by replacing the list —
     * the whole point of paging is that what you were reading stays readable.
     */
    data class Ready(
        val metadata: KeyMetadata,
        val value: LoadedValue,
        val paging: Boolean = false,
    ) : ValueState

    /**
     * The key is not there.
     *
     * Its own state rather than a failure, and §3.5 asks for exactly that: a key that
     * expires while it is open is Redis working correctly, and turning the workspace
     * red for it would be this application misreporting a cache doing its job.
     */
    data class Missing(val key: KeyRef) : ValueState

    /** The key exists and holds a type this build has no viewer for. */
    data class Unsupported(val key: KeyRef, val reported: String) : ValueState

    /** Something else went wrong, and it belongs to this pane rather than the window. */
    data class Failed(val key: KeyRef, val failure: Failure) : ValueState
}

/** Which rendering of a string is showing. */
enum class TextView { RAW, JSON }

/**
 * One key's value, read a page at a time in whichever shape its type has.
 *
 * Two rules from §3.6 shape everything here. The first is that the type is re-read
 * before every page — which `:core` does, so what this has to do is *react* to the
 * answer: a key that has been deleted and recreated as something else arrives as
 * [DbError.KeyTypeChanged] carrying the type it is now, and the viewer reloads as that
 * rather than reporting a `WRONGTYPE` nobody can act on. The second is that a
 * disappeared key is not an error: it is [ValueState.Missing], the rest of the
 * workspace is untouched, and the browser beside it keeps working.
 *
 * JSON is detected rather than assumed, and only where it can be done honestly: on a
 * string that has been read all the way to its end, whose every window decoded as
 * UTF-8, and which is smaller than [KeyValueLimits.jsonBytes]. The parse is
 * [JsonFormat]'s, which reformats without reinterpreting a single value — so the
 * pretty view is the same document with different whitespace, and the raw view is
 * always one click away and is what a copy takes.
 */
class RedisValueViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
    private val limits: KeyValueLimits = KeyValueLimits(),
) {
    var connectionId: ConnectionId? by mutableStateOf(null)
        private set

    var state: ValueState by mutableStateOf(ValueState.Idle)
        private set

    /**
     * Something that happened to the key while it was open, said once.
     *
     * A type change is the case this exists for: the viewer has already reloaded as
     * the new type by the time the user reads it, so this is a note about what
     * happened rather than a question about what to do.
     */
    var notice: String? by mutableStateOf(null)
        private set

    /** Which rendering of a string is showing. Reset with every key. */
    var textView: TextView by mutableStateOf(TextView.RAW)
        private set

    /**
     * The string, reformatted as JSON, or `null` when it is not JSON this can read.
     *
     * Held rather than computed on demand: it is a parse of up to a megabyte, and a
     * property read during layout is a parse per frame.
     */
    var json: String? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /** The key whose value is open, whatever state it is in. */
    val key: KeyRef?
        get() = when (val state = state) {
            is ValueState.Ready -> state.metadata.key
            is ValueState.Missing -> state.key
            is ValueState.Unsupported -> state.key
            is ValueState.Failed -> state.key
            else -> pending
        }

    /** The key a load is in flight for, so [key] can name it while it loads. */
    private var pending: KeyRef? by mutableStateOf(null)

    /** Points the pane at a connection. A different one closes whatever was open. */
    fun show(id: ConnectionId?) {
        if (id == connectionId) return
        clear()
        connectionId = id
    }

    /**
     * Opens [key], reading its metadata fresh before its first page.
     *
     * §3.5: the metadata read is independent of the scan page the key came from, so
     * what the header says about TTL and size is what is true now rather than what was
     * true when the page was collected — which for a key with a five-second TTL is the
     * difference between a countdown and a fiction.
     */
    fun open(key: KeyRef) {
        val id = connectionId ?: return
        job?.cancel()
        pending = key
        notice = null
        json = null
        textView = TextView.RAW
        state = ValueState.Loading
        job = scope.launch { load(id, key, from = null) }
    }

    /** Re-reads the open key from its first page. */
    fun refresh() {
        key?.let { open(it) }
    }

    /**
     * Reads the next page and adds it to what is on screen.
     *
     * Ignored when the value is complete, which is what stops a **Show more** left
     * enabled by a race from walking off the end of a collection.
     */
    fun more() {
        val id = connectionId ?: return
        val ready = state as? ValueState.Ready ?: return
        if (!ready.value.hasMore || ready.paging) return
        state = ready.copy(paging = true)
        job?.cancel()
        job = scope.launch { load(id, ready.metadata.key, from = ready.value) }
    }

    fun showRaw() {
        textView = TextView.RAW
    }

    fun showJson() {
        if (json != null) textView = TextView.JSON
    }

    fun dismissNotice() {
        notice = null
    }

    /** Closes whatever is open. Called when the vault locks or the connection closes. */
    fun clear() {
        job?.cancel()
        job = null
        pending = null
        state = ValueState.Idle
        notice = null
        json = null
        textView = TextView.RAW
    }

    /**
     * Reads one page, continuing [from] when there is something to continue.
     *
     * The metadata is read on the first page only. A continuation that re-read it
     * would be three more commands per **Show more** to answer a question the header
     * is already showing — and §3.5 gives the user a refresh for when they want it
     * asked again.
     */
    private suspend fun load(id: ConnectionId, key: KeyRef, from: LoadedValue?) {
        try {
            val metadata = if (from == null) {
                service.redisKey(id, key).also { if (!it.settled()) return }
            } else {
                (state as? ValueState.Ready)?.metadata ?: return
            }
            val type = metadata.type ?: return
            val page = service.redisValue(id, request(key, type, from))
            currentCoroutineContext().ensureActive()
            val value = from.append(page)
            state = ValueState.Ready(metadata, value)
            json = value.asJson()
            // A further page can turn a value that parsed into one that does not —
            // a JSON tab still showing after that would be showing a stale document.
            if (json == null) textView = TextView.RAW
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (problem: Throwable) {
            currentCoroutineContext().ensureActive()
            when (val error = problem.asDbError()) {
                // §3.6's contract: the key is something else now, and the reply says
                // what. Reopening it reloads the viewer that type actually needs.
                is DbError.KeyTypeChanged -> retype(key, error)
                else -> state = ValueState.Failed(key, problem.toFailure())
            }
        }
    }

    /** Puts a key that is missing or unviewable into its own state, and stops. */
    private fun KeyMetadata.settled(): Boolean = when {
        !exists -> {
            state = ValueState.Missing(key)
            false
        }

        type == null -> {
            state = ValueState.Unsupported(key, unsupportedType.orEmpty())
            false
        }

        else -> true
    }

    private fun retype(key: KeyRef, error: DbError.KeyTypeChanged) {
        if (error.actual == null) {
            notice = "That key expired or was deleted while it was open."
            state = ValueState.Missing(key)
            return
        }
        // After the reopen, which clears the notice it would otherwise be setting.
        open(key)
        notice = "That key is a ${error.actual} now, not a ${error.expected}. Reloaded it as one."
    }

    private fun request(key: KeyRef, type: KeyType, from: LoadedValue?) = when (from) {
        null -> ValueRequest(key = key, type = type)
        is LoadedValue.Text -> ValueRequest(key, type, offset = (from.nextOffset ?: 0).toLong())
        is LoadedValue.Fields -> ValueRequest(key, type, cursor = from.cursor)
        is LoadedValue.Members -> ValueRequest(key, type, cursor = from.cursor)
        is LoadedValue.Scored -> ValueRequest(key, type, offset = from.nextOffset ?: 0)
        is LoadedValue.Elements -> ValueRequest(key, type, offset = from.nextOffset ?: 0)
        is LoadedValue.Entries -> ValueRequest(key, type, fromId = from.nextId)
    }

    /**
     * [page] added to what was already read.
     *
     * A page of a different shape than what is being accumulated replaces it rather
     * than being merged: that only happens when the key changed type underneath, and
     * the pages before it describe a value that no longer exists.
     */
    private fun LoadedValue?.append(page: ValuePage): LoadedValue = when (page) {
        is ValuePage.Text -> LoadedValue.Text(
            windows = (this as? LoadedValue.Text)?.windows.orEmpty() + page.content,
            length = page.length,
            // nextOffset counts from the start of the value, so it is already the
            // number of bytes read. When there is no next page the read reached the
            // end of the value, or the cap that stood in for it.
            loadedBytes = page.nextOffset ?: minOf(page.length, page.cappedAt ?: page.length),
            nextOffset = page.nextOffset,
            cappedAt = page.cappedAt,
            complete = page.complete,
            truncated = (this as? LoadedValue.Text)?.truncated == true || page.truncated,
        )

        is ValuePage.Fields -> LoadedValue.Fields(
            entries = (this as? LoadedValue.Fields)?.entries.orEmpty() + page.entries,
            cursor = page.cursor,
            complete = page.complete,
            truncated = (this as? LoadedValue.Fields)?.truncated == true || page.truncated,
        )

        is ValuePage.Members -> LoadedValue.Members(
            members = (this as? LoadedValue.Members)?.members.orEmpty() + page.members,
            cursor = page.cursor,
            complete = page.complete,
            truncated = (this as? LoadedValue.Members)?.truncated == true || page.truncated,
        )

        is ValuePage.Scored -> LoadedValue.Scored(
            members = (this as? LoadedValue.Scored)?.members.orEmpty() + page.members,
            nextOffset = page.nextOffset,
            total = page.total,
            complete = page.complete,
            truncated = (this as? LoadedValue.Scored)?.truncated == true || page.truncated,
        )

        is ValuePage.Elements -> LoadedValue.Elements(
            elements = (this as? LoadedValue.Elements)?.elements.orEmpty() + page.elements,
            nextOffset = page.nextOffset,
            total = page.total,
            complete = page.complete,
            truncated = (this as? LoadedValue.Elements)?.truncated == true || page.truncated,
        )

        is ValuePage.Entries -> LoadedValue.Entries(
            entries = (this as? LoadedValue.Entries)?.entries.orEmpty() + page.entries,
            nextId = page.nextId,
            complete = page.complete,
            truncated = (this as? LoadedValue.Entries)?.truncated == true || page.truncated,
        )
    }

    /**
     * The value as reformatted JSON, or `null` when it is not a candidate.
     *
     * Four conditions, and each one is a way the answer would otherwise be a guess: a
     * partial value could parse as JSON and be the first half of something else, a
     * window that did not decode is not a document, a value above the size threshold
     * is a parse nobody asked for on a click, and text that simply is not JSON returns
     * `null` from the printer and falls back silently.
     */
    private fun LoadedValue.asJson(): String? {
        val string = this as? LoadedValue.Text ?: return null
        if (!string.complete || string.length > limits.jsonBytes) return null
        return JsonFormat.pretty(string.text ?: return null)
    }
}
