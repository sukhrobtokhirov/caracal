package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.policy.CommandClearance
import dev.caracal.core.policy.CommandConfirmationRequired
import dev.caracal.core.result.Failure
import dev.caracal.core.result.toFailure
import dev.caracal.engine.api.CommandConsent
import dev.caracal.engine.api.CommandLine
import dev.caracal.engine.api.CommandResult
import dev.caracal.engine.api.KeyValueLimits
import dev.caracal.engine.api.RawCommand
import dev.caracal.engine.api.TextValue
import dev.caracal.engine.api.TextValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the typed line currently amounts to. */
sealed interface ParsedLine {
    /** Nothing has been typed, or only whitespace. */
    data object Empty : ParsedLine

    /**
     * [arguments] is what would be sent, as it would be sent.
     *
     * [ambiguous] is §3.9's rule for when to show it: a line whose arguments are not
     * simply its words — one with quoting, escapes, or an empty argument in it — is a
     * line whose author and whose parser might disagree, and the parse is put on
     * screen before the command runs rather than after it has done something.
     */
    data class Ready(val arguments: List<TextValue>, val ambiguous: Boolean) : ParsedLine

    /** The line cannot be split: an unclosed quote, or a quote butted against text. */
    data class Invalid(val message: String) : ParsedLine
}

/** One command that has been run this session, and what came back. */
data class ConsoleEntry(
    val sequence: Int,
    /** The normalized name — `GET`, `CONFIG SET`. Never the arguments. */
    val label: String,
    val result: CommandResult? = null,
    val failure: Failure? = null,
)

/** A command the guard will not send until the user agrees to it, in these terms. */
data class PendingCommand(val command: RawCommand, val clearance: CommandClearance.Confirm)

/**
 * The raw command console.
 *
 * Two things about it are load-bearing and neither is obvious from the screen.
 *
 * The first is that **the guard is not here.** [RedisCommandGuard] runs inside
 * `:core`, on the way to the server, and this view model finds out what it decided by
 * being told — a refusal arrives as a failure and a question arrives as
 * [CommandConfirmationRequired]. That ordering is what makes §3.10's "the check is
 * server-side and cannot be bypassed" true of a desktop application with no server in
 * it: there is no path from this class to Redis that does not pass the guard, so a
 * mistake in this file cannot produce a `FLUSHALL`.
 *
 * The second is that **nothing here is written down.** §3.9 asks for the console's
 * history to stay in memory for the session, and the reason is that a Redis command's
 * arguments are where its secrets are: `AUTH`, `CONFIG SET requirepass`, a token being
 * written to a key. So the typed lines live in this object and die with the window,
 * `:core` records the command's name and its duration and not its arguments, and the
 * query history M2 writes to disk is never consulted for a Redis command.
 *
 * Consent follows from the same rule. It is an argument to one call, never a field
 * here — §3.10's "resets after one execution and is never saved" is not implemented,
 * it is unrepresentable.
 */
class RedisConsoleViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
    private val limits: KeyValueLimits = KeyValueLimits(),
) {
    var connectionId: ConnectionId? by mutableStateOf(null)
        private set

    /** The command line, as typed. */
    var line: String by mutableStateOf("")
        private set

    /** What [line] currently parses to, recomputed as it is typed. */
    var parsed: ParsedLine by mutableStateOf(ParsedLine.Empty)
        private set

    /** The command waiting on an acknowledgement, or `null`. */
    var pending: PendingCommand? by mutableStateOf(null)
        private set

    var running: Boolean by mutableStateOf(false)
        private set

    /** What has been run this session, oldest first. Memory only, and capped. */
    val entries = mutableListOf<ConsoleEntry>().toMutableStateList()

    /** The lines typed this session, for recall. Memory only, and capped. */
    private val typed = mutableListOf<String>()

    /** Where the up arrow has walked back to, or `null` when it has not been used. */
    private var recall: Int? = null

    private var sequence = 0

    private var job: Job? = null

    /** Whether Run would send anything. */
    val runnable: Boolean
        get() = connectionId != null && !running && pending == null && parsed is ParsedLine.Ready

    fun show(id: ConnectionId?) {
        if (id == connectionId) return
        clear()
        connectionId = id
    }

    fun edit(text: String) {
        line = text
        parsed = parse(text)
        // Editing leaves the recall walk: what is in the box is now the user's line
        // rather than a position in the history.
        recall = null
    }

    /**
     * Walks back through the lines typed this session, and forward again.
     *
     * The far end of the walk forward is the empty line rather than the oldest entry,
     * because that is where the user was before they started walking.
     */
    fun recallEarlier() {
        if (typed.isEmpty()) return
        val position = ((recall ?: typed.size) - 1).coerceAtLeast(0)
        recall = position
        line = typed[position]
        parsed = parse(line)
    }

    fun recallLater() {
        val position = recall ?: return
        val next = position + 1
        if (next >= typed.size) {
            recall = null
            line = ""
            parsed = ParsedLine.Empty
            return
        }
        recall = next
        line = typed[next]
        parsed = parse(line)
    }

    /**
     * Sends what is in the box, once the guard is satisfied.
     *
     * The command is built here, from the parse that is already on screen, and handed
     * over whole. §3.9's "the API accepts an argument array, never a shell-like command
     * string" is the reason: a line re-split somewhere downstream would be a line
     * parsed twice, and the guard would then be inspecting a different command from the
     * one Redis receives.
     */
    fun run() {
        val id = connectionId ?: return
        if (running || pending != null) return
        if (parsed !is ParsedLine.Ready) return
        val command = try {
            CommandLine.command(line)
        } catch (problem: Throwable) {
            parsed = ParsedLine.Invalid(problem.toFailure().message)
            return
        }
        remember(line)
        send(id, command, CommandConsent.None)
    }

    /**
     * Agrees to the pending command and sends it.
     *
     * The typed phrase is checked here as well as in the dialog that collected it, for
     * the reason M2's write confirmation gives: a rule enforced only inside a
     * composable holds until somebody adds a second way to press the button. `:core`
     * checks it a third time, and that is the one that counts.
     */
    fun confirm(acknowledgement: String = "") {
        val id = connectionId ?: return
        val waiting = pending ?: return
        if (!waiting.clearance.satisfiedBy(acknowledgement)) return
        pending = null
        send(id, waiting.command, CommandConsent.Given(acknowledgement))
    }

    /** Dismisses the question without running anything. */
    fun cancelConfirmation() {
        pending = null
    }

    /** Empties the transcript. The lines recall walks go with it. */
    fun clearHistory() {
        entries.clear()
        typed.clear()
        recall = null
    }

    /** Drops everything. Called when the vault locks or the connection closes. */
    fun clear() {
        job?.cancel()
        job = null
        running = false
        pending = null
        line = ""
        parsed = ParsedLine.Empty
        clearHistory()
    }

    private fun send(id: ConnectionId, command: RawCommand, consent: CommandConsent) {
        running = true
        lateinit var mine: Job
        mine = scope.launch {
            try {
                val result = service.runCommand(id, command, consent)
                record(ConsoleEntry(++sequence, result.command, result = result))
                line = ""
                parsed = ParsedLine.Empty
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (question: CommandConfirmationRequired) {
                // Not a failure and not a transcript entry: nothing has been run, and
                // the user is about to be asked whether it should be.
                pending = PendingCommand(command, question.clearance)
            } catch (problem: Throwable) {
                record(ConsoleEntry(++sequence, command.label, failure = problem.toFailure()))
            } finally {
                // Only if this is still the command on the wire. A cancelled job's
                // `finally` runs whenever its blocking Lettuce call finally returns,
                // which can be seconds after `clear()` already reset the flag and a
                // second command was sent — and clearing it there let a third command
                // past the guard in `run()`, orphaning the second one's job while its
                // reply was still coming back.
                if (job === mine) running = false
            }
        }
        job = mine
    }

    private fun record(entry: ConsoleEntry) {
        entries += entry
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    private fun remember(text: String) {
        recall = null
        if (typed.lastOrNull() == text) return
        typed += text
        while (typed.size > MAX_ENTRIES) typed.removeAt(0)
    }

    /**
     * Splits [text] the way it will be split when it runs.
     *
     * The same [CommandLine] the command is built from, so the preview on screen and
     * the arguments on the wire cannot drift apart. The arguments are rendered through
     * [TextValues] because a `\xff` escape produces a byte that is not text, and a
     * preview that showed it as a replacement character would be showing something
     * other than what is about to be sent.
     */
    private fun parse(text: String): ParsedLine {
        if (text.isBlank()) return ParsedLine.Empty
        val arguments = try {
            CommandLine.split(text)
        } catch (problem: Throwable) {
            return ParsedLine.Invalid(problem.toFailure().message)
        }
        if (arguments.isEmpty()) return ParsedLine.Empty
        // Ambiguous exactly when the arguments are not simply the line's words. That
        // covers quoting, escapes, and an empty argument, and leaves an ordinary
        // `GET user:42` without a preview nobody needs.
        val words = text.trim().split(WHITESPACE)
        val plain = arguments.size == words.size &&
            arguments.zip(words).all { (argument, word) -> String(argument, Charsets.UTF_8) == word }
        return ParsedLine.Ready(
            arguments = arguments.map { TextValues.of(it, limits.elementBytes) },
            ambiguous = !plain,
        )
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** Transcript and recall depth. A console is not a log file. */
        const val MAX_ENTRIES = 200
    }
}
