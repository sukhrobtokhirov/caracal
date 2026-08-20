package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dbide.app.EditorRun
import dev.dbide.app.EditorViewModel
import dev.dbide.app.ExportRun
import dev.dbide.app.ExportText
import dev.dbide.app.ExportViewModel
import dev.dbide.core.export.ExportEligibility
import java.awt.Cursor

/**
 * The query workspace: the editor above, whatever the last run produced below.
 *
 * One editor and one result, as §2.3's scope says — multiple result grids for one
 * script are M4's problem, along with the tabs that would hold them.
 *
 * The divider between the two is draggable, because which half matters depends
 * entirely on what you are doing: writing a query wants text, reading forty columns
 * wants grid.
 */
@Composable
fun QueryPane(
    model: EditorViewModel,
    export: ExportViewModel,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fraction by remember { mutableFloatStateOf(DEFAULT_SPLIT) }

    // A report naming a file written from a different query is a sentence about
    // something that is no longer on screen. An export still streaming is left to
    // finish: running a new query is not a request to abandon it.
    val grid = (model.run as? EditorRun.Done)?.grid
    LaunchedEffect(grid) { export.forget() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        Column(modifier = Modifier.fillMaxSize()) {
            SqlEditor(model, modifier = Modifier.weight(fraction))
            Splitter(
                onDrag = { delta ->
                    if (height > 0) fraction = (fraction + delta / height).coerceIn(MIN_SPLIT, MAX_SPLIT)
                },
            )
            Box(modifier = Modifier.weight(1f - fraction)) { ResultArea(model, export, onCopy) }
        }
    }

    // §2.4's gate. It is a dialog rather than something drawn in the pane precisely
    // because it has to be in the way: this is the one interaction in the editor
    // that must not be completable without being noticed.
    model.pending?.let { pending ->
        WriteConfirmation(
            pending = pending,
            onConfirm = model::confirm,
            onCancel = model::cancelConfirmation,
        )
    }
}

/** What the last run left behind, in whichever of its five states it ended. */
@Composable
private fun ResultArea(model: EditorViewModel, export: ExportViewModel, onCopy: (String) -> Unit) {
    when (val run = model.run) {
        EditorRun.Idle -> EmptyState(
            title = "No result yet",
            detail = "Run a statement to see its result.",
            description = "query-idle",
        )

        is EditorRun.Running -> Running(model)

        is EditorRun.Done -> Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) { ResultGrid(run.grid, onCopy = onCopy) }
            // A statement that returned no columns has nothing to write to a file,
            // and offering to export one would be offering an empty document.
            if (run.grid.result.columns.isNotEmpty()) {
                Hairline()
                ExportStrip(model, export, run)
            }
        }

        // Cancelled is not a failure and is not drawn as one: the user asked for the
        // statement to stop, and it stopped.
        EditorRun.Cancelled -> EmptyState(
            title = "Cancelled.",
            detail = "The statement was stopped before it returned. Nothing was left half-read.",
            description = "query-cancelled",
        )

        is EditorRun.Failed -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Space.xl)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            ErrorBanner(failure = run.failure, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A statement is on the server. The editor stays usable; only Run is unavailable. */
@Composable
private fun Running(model: EditorViewModel) {
    val target = (model.run as? EditorRun.Running)?.target
    Column(
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "query-running" },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                text = "Running…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = Space.lg),
            )
        }
        target?.let {
            Text(
                // The first line of what was sent, so a long-running query says which
                // one it is without the user scrolling back through the script.
                text = firstLine(it.sql),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.md, start = Space.xxl, end = Space.xxl),
            )
        }
    }
}

/**
 * The draggable boundary between the two halves.
 *
 * Wider than the rule it draws, because a 1px drag target is a 1px drag target. The
 * hairline is what the user sees; the seven points around it are what they can
 * actually hit.
 */
@Composable
private fun Splitter(onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .hoverHighlight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            }
            .semantics { contentDescription = "query-splitter" },
        contentAlignment = Alignment.Center,
    ) {
        Hairline()
    }
}

/**
 * The export control, and the one sentence §2.10 requires next to it.
 *
 * The sentence is always there rather than hidden behind a tooltip, because what it
 * says is the thing a user would otherwise get wrong: this does not save the grid,
 * it runs the statement again. A result that has been sitting on screen for ten
 * minutes can export as something visibly different, and finding that out from the
 * file is finding it out too late.
 *
 * When the statement does not qualify — a script of several, a write, an unfinished
 * quote — the same line carries `:core`'s refusal instead. A disabled button with no
 * explanation beside it is a bug report waiting to be filed.
 */
@Composable
private fun ExportStrip(model: EditorViewModel, export: ExportViewModel, run: EditorRun.Done) {
    val connection = model.connection ?: return
    val sql = run.target.sql
    val refusal = (remember(sql) { export.eligibility(sql) } as? ExportEligibility.Refused)?.refusal

    val state = export.run
    val failed = state is ExportRun.Failed
    val message = when (state) {
        ExportRun.Idle -> refusal?.message ?: ExportText.RERUN
        is ExportRun.Running -> ExportText.running(state.path)
        is ExportRun.Done -> ExportText.done(state.path, state.report)
        ExportRun.Cancelled -> "Export cancelled. No file was written."
        is ExportRun.Failed -> state.failure.message
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Dbide.colors.paneHeader)
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(
            text = "Export CSV…",
            onClick = { export.start(connection.id, sql, connection.name) },
            description = "export-start",
            enabled = refusal == null && !export.running,
        )
        if (export.running) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            ToolButton(
                text = "Stop",
                onClick = export::cancel,
                description = "export-stop",
                emphasis = ToolEmphasis.DANGER,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { contentDescription = "export-status" },
        )
    }
}

/**
 * The opening line of [sql], bounded.
 *
 * A statement that is running is identified by its first line, which is where a
 * person put the verb and the table. Showing the whole thing would push the spinner
 * off a short pane.
 */
private fun firstLine(sql: String, limit: Int = 90): String {
    val line = sql.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length <= limit) line else line.take(limit - 1) + "…"
}

private const val DEFAULT_SPLIT = 0.42f
private const val MIN_SPLIT = 0.15f
private const val MAX_SPLIT = 0.85f
