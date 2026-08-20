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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import dev.dbide.app.EditorRun
import dev.dbide.app.EditorViewModel
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
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fraction by remember { mutableFloatStateOf(DEFAULT_SPLIT) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        Column(modifier = Modifier.fillMaxSize()) {
            SqlEditor(model, modifier = Modifier.weight(fraction))
            Splitter(
                onDrag = { delta ->
                    if (height > 0) fraction = (fraction + delta / height).coerceIn(MIN_SPLIT, MAX_SPLIT)
                },
            )
            Box(modifier = Modifier.weight(1f - fraction)) { ResultArea(model, onCopy) }
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
private fun ResultArea(model: EditorViewModel, onCopy: (String) -> Unit) {
    when (val run = model.run) {
        EditorRun.Idle -> Note("Run a statement to see its result.", "query-idle")

        is EditorRun.Running -> Running(model)

        is EditorRun.Done -> ResultGrid(run.grid, onCopy = onCopy)

        // Cancelled is not a failure and is not drawn as one: the user asked for the
        // statement to stop, and it stopped.
        EditorRun.Cancelled -> Note("Cancelled.", "query-cancelled")

        is EditorRun.Failed -> Box(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
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
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = "Running…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        target?.let {
            Text(
                // The first line of what was sent, so a long-running query says which
                // one it is without the user scrolling back through the script.
                text = firstLine(it.sql),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String, description: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/** The draggable boundary between the two halves. */
@Composable
private fun Splitter(onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .background(MaterialTheme.colorScheme.surface)
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
        HorizontalDivider()
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
