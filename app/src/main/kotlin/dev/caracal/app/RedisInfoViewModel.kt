package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.result.Failure
import dev.caracal.core.result.toFailure
import dev.caracal.engine.api.ServerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the server dashboard has to show. */
sealed interface InfoState {
    data object Idle : InfoState

    data object Loading : InfoState

    /**
     * [info] is whatever `INFO` yielded, which is routinely less than everything.
     *
     * A restricted or partial summary is a success here, not a failure: §3.8's rule is
     * that the dashboard degrades to fewer cards rather than to an error, because an
     * ACL that grants read access and withholds `INFO` is the ordinary way to be handed
     * a production cache.
     */
    data class Ready(val info: ServerInfo) : InfoState

    /** `INFO` could not be reached at all — the connection, rather than a permission. */
    data class Failed(val failure: Failure) : InfoState
}

/**
 * The `INFO` dashboard: read once when the Redis workspace opens, and on request.
 *
 * No polling, which §3.8 asks for explicitly and which is worth keeping honest about.
 * A dashboard that refreshed itself every second would be this application issuing a
 * command per second per open window against a single-threaded server, for a screen
 * nobody is watching most of the time — and the numbers it would animate are counters,
 * not a live trace. The refresh is a button, and it is the user's.
 */
class RedisInfoViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
) {
    var connectionId: ConnectionId? by mutableStateOf(null)
        private set

    var state: InfoState by mutableStateOf(InfoState.Idle)
        private set

    /** When the summary on screen was read, for the line that says how old it is. */
    var readAt: Long? by mutableStateOf(null)
        private set

    private var job: Job? = null

    val loading: Boolean get() = state is InfoState.Loading

    /**
     * Points the dashboard at a connection and reads it once.
     *
     * Being handed the same connection is not a re-read. The pane is rebuilt every
     * time the workspace switches tabs, and a dashboard that fetched on every
     * composition would be a poll with extra steps.
     */
    fun show(id: ConnectionId?) {
        if (id == connectionId) return
        job?.cancel()
        connectionId = id
        state = InfoState.Idle
        readAt = null
        if (id != null) refresh()
    }

    fun refresh() {
        val id = connectionId ?: return
        job?.cancel()
        state = InfoState.Loading
        job = scope.launch {
            state = try {
                InfoState.Ready(service.serverMetrics(id)).also { readAt = System.currentTimeMillis() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                InfoState.Failed(problem.toFailure())
            }
        }
    }

    /** Drops the summary. Locking must not leave a server's numbers on screen. */
    fun clear() {
        job?.cancel()
        job = null
        connectionId = null
        state = InfoState.Idle
        readAt = null
    }
}
