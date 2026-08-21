package dev.dbide.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionService
import kotlinx.coroutines.CoroutineScope

/**
 * A tab's name for as long as this process is running.
 *
 * Opaque and never reused, so a close that races a click cannot land on whichever tab
 * has since taken the old one's place in the strip. There is nothing to persist it
 * to: §4.3 keeps tabs in memory deliberately, because a draft can hold a password in
 * a `WHERE` clause.
 */
@JvmInline
value class TabId internal constructor(internal val value: Long)

/**
 * One SQL tab: an editor, the export taken from its result, and the identity the
 * strip draws.
 *
 * The editor already was a tab in everything but name — it owns a connection, a
 * script, a running query, and one result — so a tab is that object plus the two
 * things only a strip needs: which one it is, and when it was last worked in.
 *
 * The export is per tab rather than per window because an export is a second
 * execution of *this* tab's statement. One shared between tabs would report a file
 * written from a query the user has since switched away from, and cancelling it from
 * the tab now on screen would stop work started somewhere else.
 */
class EditorTab internal constructor(
    val id: TabId,
    val editor: EditorViewModel,
    val export: ExportViewModel,
) {
    /**
     * When this tab was last the one on screen, on the strip's own counter.
     *
     * A counter rather than a clock, for the reason [dev.dbide.core.history.HistoryCursor]
     * keys on a row id: wall time can step backwards under NTP, and "the tab I was
     * last in" then resolves to a tab the user has not touched in an hour.
     */
    internal var activated: Long by mutableStateOf(0L)

    /** What the connection is, or `null` once the tab has been closed. */
    val connection: ConnectionConfig? get() = editor.connection

    val connectionId: ConnectionId? get() = editor.connectionId

    /** What the strip calls this tab. */
    val title: String get() = TabTitle.of(editor.text.text)

    /** Whether closing it would lose a script that exists nowhere else. */
    val dirty: Boolean get() = editor.dirty

    /** Whether a statement of this tab's is on the server right now. */
    val running: Boolean get() = editor.running
}

/** Why a tab did not simply close when it was asked to. */
enum class CloseReason {
    /** It holds a script that is not saved anywhere. */
    DIRTY,

    /** A statement of its is on the server. */
    RUNNING,
}

/**
 * A close waiting on an answer.
 *
 * The tab is held rather than looked up again when the dialog is answered: between
 * the question and the button the query can finish, and what closes must be the tab
 * the question named.
 */
data class PendingClose(val tab: EditorTab, val reason: CloseReason) {
    /** Whether closing would stop a query *and* lose a script. Both are said aloud. */
    val losesScript: Boolean get() = tab.dirty
}

/**
 * Every SQL tab in the window, and the rules §4.3 puts around opening and closing one.
 *
 * **The strip belongs to a connection.** [of] answers with the tabs pointed at one
 * server and the query pane draws those, which is the one arrangement in which the
 * shell bar above the strip, the object browser beside it, and the tab in front of
 * the user are all describing the same server. A single strip spanning connections
 * would put a tab that runs against production under a shell that is not red.
 *
 * What §4.3 is actually about survives that intact: a tab captures its connection
 * when it is opened and never changes it silently. Selecting a different connection
 * in the sidebar shows that connection's tabs — it does not retarget the one that was
 * on screen. Moving a tab to another server is [retarget], which is an action taken
 * on the tab itself, and it is refused while that tab has a statement running,
 * because the statement belongs to the server it was sent to.
 *
 * Nothing here is persisted, and that is a decision rather than an omission.
 */
class EditorTabs(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
    private val chooseFile: FileChooser,
) {
    /** Every open tab, in the order they were opened. */
    var tabs: List<EditorTab> by mutableStateOf(emptyList())
        private set

    /** The close being asked about, or `null` when nothing is. */
    var closing: PendingClose? by mutableStateOf(null)
        private set

    private var sequence = 0L
    private var clock = 0L

    private companion object {
        /**
         * How many tabs may hold a result at once.
         *
         * Chosen against what a person can actually be working in rather than
         * against a memory figure: more than a handful of grids in play at one time
         * is not a workflow, it is a pile. Eight is generous for the first and cheap
         * for the second — at the worst a result is allowed to be, it is the
         * difference between an eight-way cap and a twenty-way one.
         */
        const val RETAINED_RESULTS = 8
    }

    /**
     * The connections that have been given their first tab already.
     *
     * Opening a connection lands in an editor, as it did before there were tabs — but
     * only the first time. Someone who closed the last tab asked for the empty state,
     * and reselecting the connection is not a request to undo that.
     */
    private val seeded = mutableSetOf<ConnectionId>()

    /** The tabs pointed at [id], in strip order. */
    fun of(id: ConnectionId?): List<EditorTab> =
        if (id == null) emptyList() else tabs.filter { it.connectionId == id }

    /** The tab [id]'s strip is showing: the one most recently worked in. */
    fun active(id: ConnectionId?): EditorTab? = of(id).maxByOrNull { it.activated }

    /** Whether any tab holds a script that closing the window would lose. */
    val dirty: Boolean get() = tabs.any { it.dirty }

    /**
     * Points [config]'s tabs at it, and gives it one if it has never had any.
     *
     * The configuration and not the identifier, for the reason the editor took one:
     * §2.4's policy asks the connection whether it is read only and which environment
     * it is, so unticking Read only has to reach every tab already open on it.
     */
    fun show(config: ConnectionConfig?) {
        if (config == null) return
        of(config.id).forEach { it.editor.show(config) }
        if (seeded.add(config.id) && of(config.id).isEmpty()) open(config)
    }

    /**
     * Opens a tab on [config], optionally holding [sql], and makes it the active one.
     *
     * [sql] arrives from history. It is put in through the editor's own [EditorViewModel.open],
     * so a statement reopened into a fresh tab is recorded as that tab's baseline and
     * closing it again asks nothing — it is still exactly what history has.
     */
    fun open(config: ConnectionConfig, sql: String = ""): EditorTab {
        val tab = EditorTab(
            id = TabId(++sequence),
            editor = EditorViewModel(service, scope).also { it.show(config) },
            export = ExportViewModel(service, scope, chooseFile),
        )
        if (sql.isNotEmpty()) tab.editor.open(sql)
        tabs = tabs + tab
        seeded += config.id
        activate(tab)
        return tab
    }

    /** Brings [tab] to the front of its connection's strip. */
    fun activate(tab: EditorTab) {
        tab.activated = ++clock
        evict()
    }

    /**
     * Lets go of the results in tabs nobody has been in for a while.
     *
     * §4.10 asks for a bounded number of result models in memory, and tabs are the
     * only place this application keeps more than one. A single result is already
     * bounded — `ResultLimits` caps it at a thousand rows and sixteen megabytes of
     * characters — but twenty tabs holding one each is twenty times that, and twenty
     * tabs is a number §4.10 names rather than one it considers unlikely.
     *
     * Recency rather than age: the tabs someone is moving between keep their grids,
     * and the ones they opened this morning and have not looked at since give theirs
     * up. Nothing is lost that cannot be got back — the script is untouched and Run
     * sends it again — which is the whole reason this is allowed to be automatic.
     *
     * A tab with work in flight is skipped whatever its position. A statement on the
     * server has no result to release yet and is about to want one; an export is
     * streaming rows to a file somebody asked for, and cancelling it here would
     * delete a half-written file because the tab had gone quiet — which is the one
     * thing this must never do, since the whole justification for evicting
     * automatically is that nothing is lost by it.
     */
    private fun evict() {
        if (tabs.size <= RETAINED_RESULTS) return
        tabs.sortedByDescending { it.activated }
            .drop(RETAINED_RESULTS)
            .filterNot { it.running || it.export.running }
            .forEach { tab ->
                // The export report goes with the result it describes. Leaving
                // "Wrote 12 rows" under a pane that says the result is gone is two
                // answers to one question.
                if (tab.editor.release()) tab.export.clear()
            }
    }

    /**
     * A second tab on the same connection holding the same script.
     *
     * The text and the caret are copied and the running query is not: two tabs sharing
     * one execution is two tabs that would both claim its result. The copy counts as
     * unsaved from the moment it exists, because it is a script that is now in two
     * places in memory and none on disk.
     */
    fun duplicate(tab: EditorTab): EditorTab? {
        val config = tab.connection ?: return null
        val copy = open(config)
        copy.editor.edit(tab.editor.text)
        return copy
    }

    /**
     * Sends [tab] to a different server.
     *
     * The explicit action §4.3 asks for, and the only thing in this class that changes
     * a tab's connection. The script survives, the result does not — a grid is a claim
     * about one server, and the editor drops it for that reason when it is pointed
     * somewhere new. The export report goes with it: it names a file streamed from the
     * connection this tab no longer has.
     *
     * Refused while a statement is running. Cancelling someone's query to move their
     * tab is not something a menu item should do without saying so, and the menu says
     * so by being unavailable.
     */
    fun retarget(tab: EditorTab, config: ConnectionConfig) {
        if (tab.running || tab.connectionId == config.id) return
        tab.editor.show(config)
        tab.export.clear()
        activate(tab)
    }

    /**
     * Closes [tab], or asks first.
     *
     * Running is asked about before dirty, because it is the fact with a consequence
     * on a server rather than in memory — and the dialog it opens says both when both
     * are true, so "Cancel and close" is never a sentence that turns out to have also
     * meant "and throw the script away".
     */
    fun requestClose(tab: EditorTab) {
        val reason = when {
            tab.running -> CloseReason.RUNNING
            tab.dirty -> CloseReason.DIRTY
            else -> null
        }
        if (reason == null) close(tab) else closing = PendingClose(tab, reason)
    }

    /** Agrees to what the close would cost: the query is cancelled, the script is gone. */
    fun confirmClose() {
        closing?.let { close(it.tab) }
        closing = null
    }

    /** Keeps the tab, its script, and its running query. */
    fun cancelClose() {
        closing = null
    }

    /**
     * Drops every tab pointed at [id], without asking.
     *
     * Called when the connection itself is deleted, which is a question the user has
     * already been asked — and one they cannot answer differently for a tab, since
     * there would be nowhere left to run what is in it.
     */
    fun forget(id: ConnectionId) {
        of(id).forEach { close(it) }
        seeded -= id
    }

    /**
     * Drops everything. Called when the vault locks.
     *
     * Locking closes every client, so every tab is pointed at a server this process
     * can no longer reach — and a script is exactly the kind of thing locking is
     * supposed to take off the screen.
     */
    fun clear() {
        val open = tabs
        tabs = emptyList()
        closing = null
        seeded.clear()
        open.forEach { it.editor.clear(); it.export.clear() }
    }

    /** Takes [tab] out of the strip and stops everything it owns. */
    private fun close(tab: EditorTab) {
        // Out of the list first: what follows nulls the tab's connection, and a tab
        // still in the strip with no connection is a tab in nobody's strip.
        tabs = tabs - tab
        if (closing?.tab === tab) closing = null
        // Cancels the query, and deletes the partial file of an export still running.
        tab.editor.clear()
        tab.export.clear()
    }
}

/** What a tab is called, given what is in it. */
object TabTitle {

    /** A tab nobody has typed in yet. */
    const val UNTITLED: String = "Untitled"

    private const val LIMIT = 26

    /**
     * The first line of [sql], bounded, or [UNTITLED].
     *
     * Derived live rather than fixed when the tab is opened, which is what makes a
     * strip of six tabs readable without a rename command: the line a person writes
     * first is the verb and the table, and that is the answer to "which tab was the
     * orders one".
     */
    fun of(sql: String): String {
        val line = sql.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.trimEnd(';')
            ?.trim()
            ?.replace(WHITESPACE, " ")
            .orEmpty()
        return when {
            line.isEmpty() -> UNTITLED
            line.length <= LIMIT -> line
            else -> line.take(LIMIT - 1).trimEnd() + "…"
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
