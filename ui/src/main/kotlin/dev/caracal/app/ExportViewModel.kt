package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.export.CsvExport
import dev.caracal.core.export.CsvExportReport
import dev.caracal.core.export.ExportEligibility
import dev.caracal.core.export.ExportStop
import dev.caracal.core.result.Failure
import dev.caracal.core.result.toFailure
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Where the file goes, chosen by the user. `null` is the dialog being dismissed. */
typealias FileChooser = suspend (suggestion: String) -> Path?

/** What the export strip has to say about the last export that was asked for. */
sealed interface ExportRun {
    /** Nothing has been exported from this result. */
    data object Idle : ExportRun

    /** The statement is on the server again and rows are streaming to [path]. */
    data class Running(val path: Path) : ExportRun

    /**
     * The file was written. [report] says how much of the result reached it — a
     * report that is not `complete` means a limit stopped it, and the strip says so
     * rather than letting a prefix look like the whole thing.
     */
    data class Done(val path: Path, val report: CsvExportReport) : ExportRun

    /** The user stopped it. No file was left behind. Not drawn as a failure. */
    data object Cancelled : ExportRun

    data class Failed(val failure: Failure) : ExportRun
}

/**
 * One result's export: pick a file, re-run the statement, stream it out.
 *
 * Separate from [EditorViewModel] because the two lifetimes are genuinely separate.
 * An export can outlive the query that produced the grid by minutes, and cancelling
 * one must not touch the other — pressing Stop on a five-minute export is not a
 * reason for the editor to forget what it is showing.
 *
 * §2.10's re-run is the fact this whole class exists to make honest. Nothing here
 * holds a completed result waiting to be saved; the statement goes to the server a
 * second time, so a table that changed since the grid was drawn exports as it is
 * now. The UI says that in words, and this class is what makes it true.
 *
 * [chooseFile] is injected rather than called directly because a native save dialog
 * is a modal window: as a parameter it is an AWT `FileDialog` in the application and
 * a function returning a temporary path in a test, which is the difference between
 * this behaviour being verified and being verified by hand.
 */
class ExportViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
    private val chooseFile: FileChooser,
) {
    var run: ExportRun by mutableStateOf(ExportRun.Idle)
        private set

    private var job: Job? = null

    val running: Boolean get() = run is ExportRun.Running

    /**
     * Whether [sql] can be exported at all, and the sentence to show when it cannot.
     *
     * Asked of `:core`, and asked again inside [start]. A rule that lives only in the
     * enabled-state of a button holds for exactly as long as nobody adds a second way
     * to press it.
     */
    fun eligibility(sql: String): ExportEligibility = ExportEligibility.of(sql)

    /**
     * Asks for a file and, if one is chosen, exports [sql] to it.
     *
     * [suggestion] is a name, not a path — [CsvExport.fileName] makes it one that
     * every platform will accept before the dialog ever sees it, so a connection
     * called `staging/eu` cannot propose a directory.
     *
     * A second call while one is running is ignored: the export owns the file it
     * picked, and two streams into one path is not a thing to let a double click do.
     */
    fun start(id: ConnectionId, sql: String, suggestion: String) {
        if (running) return
        val eligibility = eligibility(sql)
        if (eligibility is ExportEligibility.Refused) {
            run = ExportRun.Failed(Failure("export_unavailable", eligibility.refusal.message))
            return
        }
        job = scope.launch {
            val path = try {
                chooseFile(CsvExport.fileName(suggestion))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                run = ExportRun.Failed(problem.toFailure())
                return@launch
            }
            // A dismissed dialog is not a failure and is not a cancelled export. The
            // user changed their mind before anything happened; the strip goes back to
            // saying nothing.
            if (path == null) {
                run = ExportRun.Idle
                return@launch
            }
            run = ExportRun.Running(path)
            try {
                val report = service.exportCsv(id, sql, path)
                ensureActive()
                run = ExportRun.Done(path, report)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                run = ExportRun.Failed(problem.toFailure())
            }
        }
    }

    /**
     * Stops the export.
     *
     * The half-written file goes with it: `CsvExport.writeToFile` deletes a file it
     * did not finish, because a CSV that simply stops is a complete-looking document
     * with no way for a reader to know the rows ran out.
     */
    fun cancel() {
        if (!running) return
        job?.cancel()
        job = null
        run = ExportRun.Cancelled
    }

    /**
     * Forgets the last export.
     *
     * Called when the grid changes: a report naming a file written from a different
     * query is a sentence about something that is no longer on screen. An export that
     * is still running is left alone — it is writing a file the user asked for, and
     * running a new query is not a request to abandon it.
     */
    fun forget() {
        if (running) return
        run = ExportRun.Idle
    }

    /** Drops everything, running export included. Called when the vault locks. */
    fun clear() {
        job?.cancel()
        job = null
        run = ExportRun.Idle
    }
}

/** What the export strip says, in each of its states. */
object ExportText {

    /** The warning §2.10 requires: export is a second execution, not a saved result. */
    const val RERUN: String = "Export runs this statement again on the server."

    fun running(path: Path): String = "Exporting to ${path.fileName}…"

    /**
     * What was written, and whether it is everything.
     *
     * A report that stopped at a limit says which limit, because "wrote 1,000,000
     * rows" and "wrote the whole table" are different facts and only one of them is
     * safe to act on.
     */
    fun done(path: Path, report: CsvExportReport): String {
        val head = "Wrote ${GridText.count(report.rows)} to ${path.fileName}"
        val stop = when (report.stopped) {
            ExportStop.COMPLETE -> null
            ExportStop.ROW_LIMIT -> "stopped at the row limit"
            ExportStop.SIZE_LIMIT -> "stopped at the size limit"
            ExportStop.TIME_LIMIT -> "stopped at the time limit"
        }
        return listOfNotNull("$head.", stop?.let { "$it — the file holds part of the result." })
            .joinToString(" ")
    }
}
