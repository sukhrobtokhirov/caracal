package dev.caracal.app

/**
 * What the application says out loud when something finishes without being watched.
 *
 * §4.9 asks for query completion, cancellation, and important errors to reach a
 * screen reader through a live region. The interesting half of that requirement is
 * the restriction attached to it — *without reading entire result sets*. A grid of
 * ten thousand cells that announces itself is not an accessible grid, it is a denial
 * of service with good intentions.
 *
 * So an announcement is one sentence about the shape of the outcome, never its
 * contents: how many rows, how long it took, or what went wrong. The result itself
 * stays where it is, to be read by moving through it.
 *
 * Plain functions rather than composables, so what gets said is a thing that can be
 * asserted in a headless test rather than something to be listened for.
 */
object Announcements {

    /**
     * How the last run ended, or `null` while nothing has happened worth saying.
     *
     * `Running` is deliberately silent. A statement starting is something the user
     * just did, and announcing it is announcing their own keypress back at them;
     * what they cannot see is the moment it stops.
     */
    fun of(run: EditorRun): String? = when (run) {
        EditorRun.Idle, is EditorRun.Running -> null
        is EditorRun.Done -> "Finished. ${GridText.status(run.grid.result)}"
        EditorRun.Cancelled -> "Cancelled. The statement was stopped before it returned."
        // The message is already redacted by the time it reaches here — the same
        // string the banner shows — so this adds no exposure the screen does not.
        is EditorRun.Failed -> "Failed. ${run.failure.message}"
        // Nothing happened. The result was let go to keep the window bounded, which
        // is the application's business and not news the user needs read out.
        is EditorRun.Released -> null
    }

    /**
     * How an export ended.
     *
     * An export is the one thing in the application that routinely outlives the
     * user's attention: it is started deliberately and then waited out, which is
     * exactly the case a live region exists for.
     */
    fun of(run: ExportRun): String? = when (run) {
        ExportRun.Idle, is ExportRun.Running -> null
        is ExportRun.Done -> ExportText.done(run.path, run.report)
        ExportRun.Cancelled -> "Export cancelled. No file was written."
        is ExportRun.Failed -> "Export failed. ${run.failure.message}"
    }
}
