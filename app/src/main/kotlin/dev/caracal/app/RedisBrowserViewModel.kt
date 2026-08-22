package dev.caracal.app

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.ScanPage
import dev.caracal.engine.api.ScanStop
import dev.caracal.core.result.Failure
import dev.caracal.core.result.toFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Why the traversal is where it is, in the one sentence the browser puts under the
 * list.
 *
 * Its own type rather than a formatted string in the view model, because the three
 * states differ in what the user should do next — nothing, press **Load more**, or
 * narrow the pattern — and a composable choosing between them by matching on prose
 * is a composable that changes behaviour when the prose is edited.
 */
sealed interface ScanProgress {
    /** Nothing has been asked for yet. */
    data object Idle : ScanProgress

    /** A page is being read. [pages] counts the ones already collected this action. */
    data class Scanning(val pages: Int) : ScanProgress

    /** Redis returned cursor `0`. Everything the pattern matches has been seen. */
    data object Complete : ScanProgress

    /**
     * The page ended without the traversal ending: there is more, and [stopped] says
     * which budget ran out — or `null` when the read failed and the reason is on the
     * banner beside it rather than in this list of ordinary endings.
     *
     * [empty] is the case §3.4 asks to be visible rather than mistaken for the end —
     * a page that returned no keys at all because `MATCH` filtered every batch. The
     * cursor advanced, the keyspace is being walked, and the browser has nothing to
     * show for it yet.
     */
    data class More(val stopped: ScanStop?, val empty: Boolean) : ScanProgress

    /**
     * The browser is holding as many keys as it will hold, and the keyspace has more.
     *
     * §4.10's unbounded-cache clause. Every other limit on a scan bounds one *page* —
     * iterations, elapsed time, keys returned — and none of them bounds the pile the
     * pages are added to, so a keyspace walked one **Load more** at a time grows in
     * this process without end.
     *
     * This is a different ending from [More] and says a different thing. `More` means
     * press the button again; this means the button will not help and the pattern is
     * what has to change.
     */
    data object Full : ScanProgress
}

/**
 * The Redis key browser: a bounded traversal of a keyspace, and the keys it has
 * collected so far.
 *
 * Everything here is an accumulation, never a snapshot. Redis's `SCAN` guarantees
 * that a key present for the whole traversal is returned at least once and promises
 * nothing else — a key created behind the cursor is missed, a key deleted ahead of it
 * is not seen, and a table resize can return the same key twice. So the model is a
 * list that grows as the user presses **Load more**, deduplicated across the whole
 * browsing session, and thrown away entirely by **Refresh**, which is the only thing
 * that starts a traversal over.
 *
 * The one behaviour worth naming is what happens on an *empty* page. `MATCH` filters
 * on the server, so a selective pattern produces batch after batch that matched
 * nothing while the cursor advances perfectly normally — and a browser that stopped
 * there would report "no keys" for a pattern that has plenty. So an empty page
 * continues by itself, up to [AUTO_CONTINUATIONS] times per user action, and then
 * hands the decision back rather than looping until it finds something. That bound is
 * the UI's half of §3.1: `:core` stops one *request* from running away, and this stops
 * the client from issuing them back to back for as long as the keyspace is large.
 */
class RedisBrowserViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
) {
    /** The connection being browsed, or `null` when no Redis connection is open. */
    var connectionId: ConnectionId? by mutableStateOf(null)
        private set

    /**
     * The glob in the search box, as typed.
     *
     * Separate from [appliedPattern] on purpose: editing the box must not silently
     * make the list on screen a list of something else. What was searched for is what
     * the keys came from until the search is run again.
     */
    var pattern: String by mutableStateOf("")
        private set

    /** The pattern the keys on screen were collected with. */
    var appliedPattern: String by mutableStateOf("")
        private set

    /** The type filter in force, or `null` for every type this build can browse. */
    var typeFilter: KeyType? by mutableStateOf(null)
        private set

    /** Whether keys are grouped by [delimiter], or listed exactly as they came back. */
    var grouped: Boolean by mutableStateOf(true)
        private set

    /** What separates a key's segments. Configurable, because not every keyspace uses a colon. */
    var delimiter: String by mutableStateOf(":")
        private set

    var progress: ScanProgress by mutableStateOf(ScanProgress.Idle)
        private set

    /** The last failure, kept beside the keys rather than instead of them. */
    var failure: Failure? by mutableStateOf(null)
        private set

    /** The key whose value is open, or `null`. */
    var selected: KeyRef? by mutableStateOf(null)
        private set

    /** `SCAN` calls Redis has been asked for since the last refresh. */
    var iterations: Int by mutableStateOf(0)
        private set

    /**
     * The keys seen this session, in the order Redis returned them.
     *
     * A map keyed on [KeyRef] — which compares by content — is what deduplicates
     * across pages. A repeated key keeps its original position and takes the newer
     * metadata, because the second sighting is the more recent answer about its TTL.
     */
    private val collected = linkedMapOf<KeyRef, KeyMetadata>()

    /** The keys collected so far. */
    var keys: List<KeyMetadata> by mutableStateOf(emptyList())
        private set

    private val expanded: SnapshotStateSet<String> = mutableStateSetOf()

    private var cursor: ScanCursor = ScanCursor.START

    private var job: Job? = null

    val scanning: Boolean get() = progress is ScanProgress.Scanning

    /** Whether there is more keyspace to walk. */
    val hasMore: Boolean get() = progress is ScanProgress.More

    /** The list as it is drawn: grouped by prefix, or flat. */
    /**
     * Recomputed when something it reads changes, and not once per frame.
     *
     * §4.10's "excess re-rendering". This was a `get()`, which in a Compose read path
     * means the whole list is rebuilt on every recomposition of the pane — every
     * scroll, every hover, every keystroke in the box beside it. `derivedStateOf`
     * caches the answer and invalidates it only when one of the snapshot values the
     * computation actually read has changed, which for a list like this is rarely.
     *
     * It matters most here: grouping builds a prefix trie over every key that has
     * been scanned, and a keyspace walked for a while is thousands of them.
     */
    val rows: List<KeyRow> by derivedStateOf {
        RedisKeyTree.rows(keys, expanded, delimiter, grouped)
    }

    /** The metadata for [selected], as the last page reported it. */
    val selectedMetadata: KeyMetadata? get() = selected?.let { collected[it] }

    /**
     * Points the browser at a connection, or at nothing.
     *
     * Being handed the connection already open is not a reason to throw away a
     * traversal: the pane is rebuilt whenever the workspace switches tabs, and a
     * browser that restarted its scan each time would issue a fresh walk of the
     * keyspace for a click on **Console** and back.
     */
    fun show(id: ConnectionId?) {
        if (id == connectionId) return
        reset()
        connectionId = id
        if (id != null) start()
    }

    fun edit(glob: String) {
        pattern = glob
    }

    /** Runs [pattern] as a new traversal. Everything collected under the old one goes. */
    fun search() {
        appliedPattern = pattern
        start()
    }

    /** Filters by type, which restarts the traversal: the filter is a scan argument. */
    fun filterBy(type: KeyType?) {
        if (type == typeFilter) return
        typeFilter = type
        start()
    }

    /** Whether the keys on screen were collected under a pattern or a type filter. */
    val filtered: Boolean get() = appliedPattern.isNotBlank() || typeFilter != null

    /**
     * Drops both filters and walks the keyspace again from the start.
     *
     * One action rather than two, because §4.7 asks the empty result to offer a way
     * out of it and a user looking at nothing does not want to work out which of the
     * two filters is the one hiding their keys. The box is cleared as well as the
     * applied pattern: leaving the text in it would make the button look like it had
     * done nothing.
     */
    fun clearFilters() {
        if (!filtered) return
        pattern = ""
        appliedPattern = ""
        typeFilter = null
        start()
    }

    /**
     * Groups or ungroups the keys already on screen.
     *
     * Deliberately not a reload. §3.4's rule is that the tree is a presentation of
     * keys that have already been scanned, and the clearest proof of it is that
     * switching between the two views asks the server for nothing.
     */
    fun toggleGrouping() {
        grouped = !grouped
    }

    /** Changes what a segment ends at. Regroups what is here; reads nothing. */
    fun regroupBy(separator: String) {
        if (separator == delimiter) return
        delimiter = separator
        expanded.clear()
    }

    fun toggle(path: String) {
        if (!expanded.remove(path)) expanded += path
    }

    fun expandAll() {
        expanded.clear()
        expanded += RedisKeyTree.groupPaths(keys, delimiter)
    }

    fun collapseAll() = expanded.clear()

    /** Continues the traversal from where the last page stopped. */
    fun loadMore() {
        if (scanning || connectionId == null || progress !is ScanProgress.More) return
        scan(continuing = true)
    }

    /**
     * Starts over from cursor `0`, dropping every key and the deduplication with them.
     *
     * §3.4 asks for exactly this separation from **Load more**. Keeping the collected
     * keys across a refresh would mean a key deleted since the last page stayed on
     * screen forever, because nothing in a `SCAN` result says a key is gone.
     */
    fun refresh() = start()

    fun select(key: KeyRef?) {
        selected = key
    }

    fun dismissFailure() {
        failure = null
    }

    /** Drops everything. The vault locking must not leave a keyspace on screen. */
    fun clear() {
        reset()
        connectionId = null
    }

    private fun start() {
        collected.clear()
        keys = emptyList()
        expanded.clear()
        cursor = ScanCursor.START
        iterations = 0
        scan(continuing = false)
    }

    /**
     * Reads pages until one of them has keys in it, the traversal ends, or the
     * automatic continuation budget runs out.
     *
     * The loop is here and not in `:core` because it is a *UI* budget: `:core`'s scan
     * already bounds one request by iterations, time, and page size, and what this
     * decides is how many of those requests a single click is allowed to make.
     */
    private fun scan(continuing: Boolean) {
        val id = connectionId ?: return
        job?.cancel()
        failure = null
        var pages = 0
        progress = ScanProgress.Scanning(pages)

        val started = if (continuing) cursor else ScanCursor.START
        job = scope.launch {
            var from = started
            // Assigned before the loop can break, so there is always a page to report.
            var page: ScanPage
            try {
                while (true) {
                    page = service.scanKeys(
                        id = id,
                        cursor = from,
                        match = appliedPattern.takeIf { it.isNotBlank() },
                        type = typeFilter,
                    )
                    ensureActive()
                    pages++
                    iterations += page.iterations
                    from = page.cursor
                    cursor = page.cursor
                    collect(page.keys)
                    // An empty page is not an ending and not a failure. It is the
                    // ordinary shape of a selective MATCH, and the only thing to do
                    // with one is to keep going — for a while.
                    val keepGoing =
                        page.keys.isEmpty() && !page.complete && pages <= AUTO_CONTINUATIONS && !full
                    if (!keepGoing) break
                    progress = ScanProgress.Scanning(pages)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                failure = problem.toFailure()
                // Still "more", because the cursor is still valid and the keys already
                // collected are still true: Load more is a retry, not a fresh walk.
                progress = ScanProgress.More(stopped = null, empty = keys.isEmpty())
                return@launch
            }
            progress = when {
                page.complete -> ScanProgress.Complete
                // Said before "there is more", because both are true and only one of
                // them is worth acting on: pressing Load more again would collect
                // nothing.
                full -> ScanProgress.Full
                else -> ScanProgress.More(page.stopped, empty = page.keys.isEmpty())
            }
        }
    }

    private fun collect(page: List<KeyMetadata>) {
        if (page.isEmpty()) return
        page.forEach { metadata -> collected[metadata.key] = metadata }
        keys = collected.values.toList()
    }

    /** Whether the pile has reached what this process is willing to hold. */
    private val full: Boolean get() = collected.size >= MAX_COLLECTED_KEYS

    private fun reset() {
        job?.cancel()
        job = null
        collected.clear()
        keys = emptyList()
        expanded.clear()
        cursor = ScanCursor.START
        iterations = 0
        pattern = ""
        appliedPattern = ""
        typeFilter = null
        selected = null
        failure = null
        progress = ScanProgress.Idle
    }

    internal companion object {
        /**
         * Extra pages one click may read while every batch comes back empty.
         *
         * Small on purpose. Each one is a bounded `SCAN` loop of its own, so five
         * pages is up to a hundred `SCAN` calls — enough to walk past a stretch of
         * keyspace a pattern does not match, and short of walking a large database
         * without the user having asked twice.
         */
        const val AUTO_CONTINUATIONS = 4

        /**
         * How many keys the browser will hold at once.
         *
         * The scan is bounded per page and the pile it fills was not, so a keyspace
         * walked one click at a time grew in this process for as long as someone kept
         * clicking. Ten thousand is far past the point where a flat list is how anyone
         * would find a key — the answer at that scale is a narrower pattern, which is
         * what [ScanProgress.Full] says — and it is a keyspace-shaped number rather
         * than a byte count because what a key costs is its name.
         */
        const val MAX_COLLECTED_KEYS = 10_000
    }
}
