package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.connections.ConnectionView
import dev.caracal.core.connections.TestResult
import dev.caracal.core.result.Failure
import dev.caracal.core.result.toFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Which slow operation is running, so exactly one spinner can be shown. */
enum class Activity { NONE, LOADING, SAVING, TESTING, OPENING, CLOSING, DELETING }

/** What the right-hand pane is showing. */
sealed interface Pane {
    /** Nothing is selected yet. */
    data object Empty : Pane

    /** A saved connection's details. */
    data class Detail(val id: ConnectionId) : Pane

    /** The create or edit form. */
    data class Form(val state: ConnectionFormState) : Pane
}

/**
 * Owns the connection list and everything the user can do to it.
 *
 * Every operation runs in [scope], which is tied to the window, so closing the
 * window cancels an in-flight dial rather than leaving it to finish into nothing.
 */
class ConnectionsViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
) {
    var connections: List<ConnectionView> by mutableStateOf(emptyList())
        private set

    var pane: Pane by mutableStateOf(Pane.Empty)
        private set

    var activity: Activity by mutableStateOf(Activity.NONE)
        private set

    var failure: Failure? by mutableStateOf(null)
        private set

    /** The last successful test, shown until the user does something else. */
    var testResult: TestResult? by mutableStateOf(null)
        private set

    /** The connection a delete confirmation is open for. */
    var pendingDelete: ConnectionView? by mutableStateOf(null)
        private set

    private var running: Job? = null

    val busy: Boolean get() = activity != Activity.NONE

    /** The connection the right-hand pane is about, if any. */
    val selected: ConnectionView?
        get() = when (val pane = pane) {
            is Pane.Detail -> connections.firstOrNull { it.id == pane.id }
            is Pane.Form -> pane.state.editing?.let { editing ->
                connections.firstOrNull { it.id == editing.id }
            }

            Pane.Empty -> null
        }

    fun refresh() = perform(Activity.LOADING) {
        connections = service.list()
        // A connection deleted in another window must not stay selected here.
        val pane = pane
        if (pane is Pane.Detail && connections.none { it.id == pane.id }) this.pane = Pane.Empty
    }

    fun select(id: ConnectionId) {
        pane = Pane.Detail(id)
        clearTransient()
    }

    fun startCreating() {
        pane = Pane.Form(ConnectionFormState())
        clearTransient()
    }

    fun startEditing(view: ConnectionView) {
        pane = Pane.Form(ConnectionFormState(editing = view))
        clearTransient()
    }

    fun cancelForm() {
        pane = (pane as? Pane.Form)?.state?.editing?.let { Pane.Detail(it.id) } ?: Pane.Empty
        clearTransient()
    }

    /** Creates or updates the connection the form describes. */
    fun save() {
        val form = (pane as? Pane.Form)?.state ?: return
        val draft = form.toDraft() ?: return
        perform(Activity.SAVING, onFailure = { form.showErrors(it.fields) }) {
            val saved = form.id?.let { service.update(it, draft) } ?: service.create(draft)
            connections = service.list()
            pane = Pane.Detail(saved.id)
        }
    }

    /**
     * Tests the saved connection. A connection has to exist to be tested, because
     * the credential being tested is the sealed one.
     */
    fun test(id: ConnectionId) = perform(Activity.TESTING) {
        testResult = service.test(id)
    }

    fun open(id: ConnectionId) = perform(Activity.OPENING) {
        service.open(id)
        connections = service.list()
    }

    fun close(id: ConnectionId) = perform(Activity.CLOSING) {
        service.close(id)
        connections = service.list()
    }

    /** Deleting is deliberate: the UI asks first, and this is the second step. */
    fun confirmDelete(view: ConnectionView) {
        pendingDelete = view
    }

    fun cancelDelete() {
        pendingDelete = null
    }

    fun delete(id: ConnectionId) = perform(Activity.DELETING) {
        service.delete(id)
        pendingDelete = null
        connections = service.list()
        pane = Pane.Empty
    }

    fun dismissFailure() {
        failure = null
    }

    /** Drops the list when the vault locks: a locked application shows nothing. */
    fun clear() {
        running?.cancel()
        connections = emptyList()
        pane = Pane.Empty
        pendingDelete = null
        clearTransient()
        activity = Activity.NONE
    }

    private fun clearTransient() {
        failure = null
        testResult = null
    }

    /**
     * Runs one operation at a time, reporting its progress and any failure. A second
     * click while something is in flight is ignored rather than queued, which is what
     * stops a double submission from creating two connections.
     */
    private fun perform(
        activity: Activity,
        onFailure: (Failure) -> Unit = {},
        body: suspend () -> Unit,
    ) {
        if (running?.isActive == true) return
        this.activity = activity
        failure = null
        if (activity != Activity.TESTING) testResult = null
        running = scope.launch {
            try {
                body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                val classified = problem.toFailure()
                failure = classified
                onFailure(classified)
            } finally {
                this@ConnectionsViewModel.activity = Activity.NONE
            }
        }
    }
}
