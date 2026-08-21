package dev.caracal.app

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.history.ExecutionOutcome
import dev.caracal.core.history.ExecutionRecord
import dev.caracal.core.history.HISTORY_PAGE
import dev.caracal.core.history.HistoryCursor
import dev.caracal.core.history.HistoryQuery
import dev.caracal.core.history.HistoryScope
import dev.caracal.core.result.Failure
import dev.caracal.core.result.toFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** What the panel has to show for the page it asked for. */
sealed interface HistoryLoad {
    /** The first page of this filter is on its way. Nothing is on screen yet. */
    data object Loading : HistoryLoad

    /** [entries] is what has been read so far, oldest page last. */
    data class Ready(val entries: List<ExecutionRecord>) : HistoryLoad

    /** The read failed. Whatever had been loaded is gone with the filter that read it. */
    data class Failed(val failure: Failure) : HistoryLoad
}

/**
 * The history panel: which executions to show, the pages read so far, and the two
 * deletions it can be asked for.
 *
 * Pages accumulate rather than replace, exactly as the Redis viewers' do. Reading
 * older history is reading *further back*, and a Show more that swapped the visible
 * page for the one behind it would make scrolling through an afternoon a matter of
 * remembering what the previous screen said.
 *
 * Changing a filter is not paging: it starts again from the newest entry, because the
 * cursor it holds is a place in the ordering the *old* filter produced.
 */
class HistoryViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
) {
    /** The connection being shown, or `null` for every one of them. */
    var connectionId: ConnectionId? by mutableStateOf(null)
        private set

    /** The ending being shown, or `null` for all three. */
    var outcome: ExecutionOutcome? by mutableStateOf(null)
        private set

    /**
     * What the search box holds.
     *
     * It filters the pages that have been read and nothing else, which is why the
     * panel says so next to it. A search that silently covered only the last fifty
     * statements while looking like it covered a thousand would be worse than no
     * search at all — the answer "not found" would be a lie the user has no way of
     * catching.
     */
    var search: String by mutableStateOf("")
        private set

    var state: HistoryLoad by mutableStateOf(HistoryLoad.Loading)
        private set

    /** Whether a page older than the ones on screen is on its way. */
    var loadingMore: Boolean by mutableStateOf(false)
        private set

    /** The entry whose whole statement is open, or `null` while all of them are previews. */
    var expanded: Long? by mutableStateOf(null)
        private set

    /** The deletion waiting for an answer, or `null` when none has been asked for. */
    var pendingClear: HistoryScope? by mutableStateOf(null)
        private set

    private var cursor: HistoryCursor? = null
    private var job: Job? = null

    /** Everything read so far, or an empty list in either of the other two states. */
    val entries: List<ExecutionRecord>
        get() = (state as? HistoryLoad.Ready)?.entries.orEmpty()

    /** What the list draws: the loaded pages, narrowed by whatever is in the search box. */
    /**
     * Recomputed when something it reads changes, and not once per frame.
     *
     * §4.10's "excess re-rendering". This was a `get()`, which in a Compose read path
     * means the whole list is rebuilt on every recomposition of the pane — every
     * scroll, every hover, every keystroke in the box beside it. `derivedStateOf`
     * caches the answer and invalidates it only when one of the snapshot values the
     * computation actually read has changed, which for a list like this is rarely.
     */
    val visible: List<ExecutionRecord> by derivedStateOf {
        search.trim().takeIf { it.isNotEmpty() }?.let { needle ->
            entries.filter { it.statement.contains(needle, ignoreCase = true) }
        } ?: entries
    }

    /** Whether there is provably a page behind the ones on screen. */
    val hasMore: Boolean get() = cursor != null

    /**
     * Opens the panel on one connection, or on all of them.
     *
     * Called every time the window is shown rather than only the first time. History
     * is written by the editor beside it, so a panel that kept the page it read an
     * hour ago would open on a list missing everything since.
     */
    fun open(connectionId: ConnectionId?) {
        this.connectionId = connectionId
        outcome = null
        search = ""
        expanded = null
        reload()
    }

    /** Re-reads the first page of the current filter. */
    fun reload() {
        cursor = null
        expanded = null
        load(replacing = true)
    }

    /** Reads the page older than the last one on screen. */
    fun loadMore() {
        if (cursor == null || loadingMore || state !is HistoryLoad.Ready) return
        load(replacing = false)
    }

    fun showConnection(id: ConnectionId?) {
        if (id == connectionId) return
        connectionId = id
        reload()
    }

    fun showOutcome(value: ExecutionOutcome?) {
        if (value == outcome) return
        outcome = value
        reload()
    }

    /** Filters the loaded pages. Does not go back to the store; nothing here is a query. */
    fun searchFor(text: String) {
        search = text
    }

    /** Opens one entry's whole statement, or closes the one that is open. */
    fun toggle(id: Long?) {
        expanded = if (expanded == id) null else id
    }

    // --- Deletion ------------------------------------------------------------

    /**
     * Asks for a deletion. Nothing is removed until [confirmClear].
     *
     * The scope is carried through the confirmation rather than recomputed when its
     * button is pressed: between opening the dialog and answering it the user can
     * change the connection filter behind it, and what goes has to be what the dialog
     * said would go.
     */
    fun askClear(scope: HistoryScope) {
        pendingClear = scope
    }

    fun cancelClear() {
        pendingClear = null
    }

    fun confirmClear() {
        val target = pendingClear ?: return
        pendingClear = null
        scope.launch {
            try {
                service.clearHistory(target)
                reload()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                state = HistoryLoad.Failed(problem.toFailure())
            }
        }
    }

    /** Drops everything read. Called when the vault locks. */
    fun clear() {
        job?.cancel()
        job = null
        cursor = null
        connectionId = null
        outcome = null
        search = ""
        expanded = null
        pendingClear = null
        state = HistoryLoad.Loading
    }

    private fun load(replacing: Boolean) {
        job?.cancel()
        val existing = if (replacing) emptyList() else entries
        if (replacing) state = HistoryLoad.Loading else loadingMore = true

        job = scope.launch {
            try {
                val page = service.history(
                    HistoryQuery(
                        connectionId = connectionId,
                        outcome = outcome,
                        olderThan = if (replacing) null else cursor,
                        limit = HISTORY_PAGE,
                    ),
                )
                ensureActive()
                cursor = page.next
                state = HistoryLoad.Ready(existing + page.items)
                loadingMore = false
            } catch (cancellation: CancellationException) {
                // Deliberately without a `finally`. This job was cancelled because a
                // newer one replaced it, and that one has already set the flags for
                // what *it* is doing — clearing them here would stop the spinner the
                // request still in flight is drawing.
                throw cancellation
            } catch (problem: Throwable) {
                state = HistoryLoad.Failed(problem.toFailure())
                loadingMore = false
            }
        }
    }
}
