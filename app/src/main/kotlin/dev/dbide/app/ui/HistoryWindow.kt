package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dbide.app.HistoryFormat
import dev.dbide.app.HistoryLoad
import dev.dbide.app.HistoryViewModel
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.history.ExecutionOutcome
import dev.dbide.core.history.ExecutionRecord
import dev.dbide.core.history.HistoryScope
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the panel can do with an entry, wired by the workspace that owns both it and
 * the tabs.
 *
 * [openInEditor] and [openInNewTab] are `null` when there is nowhere to put a
 * statement — no PostgreSQL connection is open, or the entry belongs to a different
 * server than the one the workspace has in front of it. Both cases are drawn as a
 * disabled button with the reason beside it rather than as no button at all: an
 * action that appears and disappears teaches the user nothing about why.
 *
 * The two are §4.2's pair, and the difference between them is whose work is at risk.
 * Opening in the tab already on screen may replace a script and asks first;
 * [openInNewTab] cannot lose anything, which is why it is the one that is offered
 * first.
 */
class HistoryActions(
    val copy: (String) -> Unit,
    val openInEditor: ((ExecutionRecord) -> Unit)?,
    val openInNewTab: ((ExecutionRecord) -> Unit)?,
    val editorConnection: ConnectionId?,
)

/**
 * Query history: what was run, on which server, how it ended, and how to get it back.
 *
 * A window rather than a pane, for the reason every other window in this application
 * is one — it is a thing you go to, do something with, and leave, and the workspace
 * behind it is exactly where it was. It is also the one surface that spans
 * connections: the tab strip belongs to one open server, and "what did I run this
 * morning" is rarely a question about only one of them.
 *
 * Reopening never runs anything. The statement is put in the editor and the caret is
 * left at the end of it; every confirmation §2.4 asks for is still asked for when Run
 * is pressed. A panel that re-executed on a click would be a panel that fires the
 * `DELETE` you came here to look at.
 */
@Composable
fun HistoryWindow(
    model: HistoryViewModel,
    connections: List<ConnectionView>,
    actions: HistoryActions,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    val byId = remember(connections) { connections.associateBy { it.id } }
    val scoped = model.connectionId?.let(byId::get)

    AppDialog(
        title = "Query history",
        subtitle = "Kept on this machine only, and never sent anywhere.",
        description = "history-window",
        icon = { Glyph(Glyphs.HISTORY, size = 18) },
        onDismiss = onDismiss,
        scrolling = false,
        rail = {
            RailHeading("Connection")
            RailItem(
                label = "All connections",
                detail = "Everything this machine has run.",
                description = "history-filter-all",
                selected = model.connectionId == null,
                onClick = { model.showConnection(null) },
            )
            connections.forEach { view ->
                RailItem(
                    label = view.config.name,
                    detail = view.config.environment.wire,
                    description = "history-filter-${view.id.value}",
                    selected = model.connectionId == view.id,
                    onClick = { model.showConnection(view.id) },
                    leading = { EngineLogo(view.config.engine, size = 13.dp, described = false) },
                )
            }
        },
        footer = {
            ToolButton(
                text = "Clear this connection…",
                onClick = { scoped?.let { model.askClear(HistoryScope.OneConnection(it.id)) } },
                description = "history-clear-connection",
                enabled = scoped != null,
                emphasis = ToolEmphasis.DANGER,
            )
            ToolButton(
                text = "Clear all…",
                onClick = { model.askClear(HistoryScope.Everything) },
                description = "history-clear-all",
                emphasis = ToolEmphasis.DANGER,
            )
            Box(modifier = Modifier.weight(1f))
            ToolButton(text = "Close", onClick = onDismiss, description = "history-close")
        },
    ) {
        FilterStrip(model)
        Hairline()
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = model.state) {
                HistoryLoad.Loading -> Loading()
                is HistoryLoad.Failed -> Box(
                    modifier = Modifier.fillMaxSize().padding(Space.xxl),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ErrorBanner(failure = state.failure, modifier = Modifier.fillMaxWidth())
                }

                is HistoryLoad.Ready -> Entries(model, byId, actions, zone, today)
            }
        }
    }

    model.pendingClear?.let { scope ->
        ClearHistoryConfirmation(
            scope = scope,
            name = (scope as? HistoryScope.OneConnection)?.let { byId[it.id]?.config?.name },
            onConfirm = model::confirmClear,
            onCancel = model::cancelClear,
        )
    }
}

/** The outcome filter, the search box, and what the two of them are showing. */
@Composable
private fun FilterStrip(model: HistoryViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Dbide.colors.paneHeader)
            .padding(horizontal = Space.lg, vertical = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuButton(
            text = model.outcome?.let(HistoryFormat::outcome) ?: "Any outcome",
            description = "history-outcome-filter",
            actions = buildList {
                add(
                    MenuAction(
                        label = "Any outcome",
                        description = "history-outcome-any",
                        onClick = { model.showOutcome(null) },
                    ),
                )
                ExecutionOutcome.entries.forEach { outcome ->
                    add(
                        MenuAction(
                            label = HistoryFormat.outcome(outcome),
                            description = "history-outcome-${outcome.stored}",
                            onClick = { model.showOutcome(outcome) },
                        ),
                    )
                }
            },
        )
        InlineField(
            value = model.search,
            onValueChange = model::searchFor,
            description = "history-search",
            placeholder = "Search loaded entries",
            monospace = false,
            modifier = Modifier.width(240.dp),
        )
        Text(
            // Said plainly rather than implied, because the honest description of this
            // box is narrower than the one a user assumes: it searches what has been
            // read, and "no matches" on page one of a thousand statements would
            // otherwise be an answer the user has no way of catching as wrong.
            text = "Searches the entries loaded so far.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        ToolButton(text = "Refresh", onClick = model::reload, description = "history-refresh")
    }
}

/**
 * The list, grouped by day.
 *
 * Grouping is done over the entries on screen rather than asked of the store: a day
 * is a fact about a timestamp in the reader's own zone, and a `GROUP BY` in SQLite
 * would be the database deciding which side of midnight the user lives on.
 */
@Composable
private fun Entries(
    model: HistoryViewModel,
    connections: Map<ConnectionId, ConnectionView>,
    actions: HistoryActions,
    zone: ZoneId,
    today: LocalDate,
) {
    val visible = model.visible
    if (visible.isEmpty()) {
        EmptyList(model)
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        var heading: LocalDate? = null
        visible.forEach { record ->
            val day = HistoryFormat.day(record.executedAt, zone)
            if (day != heading) {
                heading = day
                item(key = "day-$day") { DayHeading(HistoryFormat.day(day, today)) }
            }
            item(key = record.id ?: record.hashCode()) {
                EntryRow(
                    record = record,
                    connection = connections[record.connectionId],
                    // The connection is named on the row only when the list spans more
                    // than one. Repeating "Local PG" down forty rows of a filter that
                    // is already showing one server is a column of noise.
                    showConnection = model.connectionId == null,
                    // An entry with no id has not been written yet and cannot be
                    // named — without the null check, every such row would open and
                    // close together.
                    expanded = record.id != null && model.expanded == record.id,
                    onToggle = { model.toggle(record.id) },
                    actions = actions,
                    zone = zone,
                )
            }
        }
        if (model.hasMore) {
            item(key = "more") { ShowMore(model) }
        }
    }
}

@Composable
private fun DayHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(Dbide.colors.chrome)
            .padding(horizontal = Space.lg, vertical = Space.sm)
            .semantics { contentDescription = "history-day" },
    )
}

/**
 * One execution.
 *
 * Collapsed it is a preview on one line; expanded it is the statement exactly as it
 * was submitted, whitespace and all, because that is the form someone is about to put
 * back in an editor and compare against what they have now.
 */
@Composable
private fun EntryRow(
    record: ExecutionRecord,
    connection: ConnectionView?,
    showConnection: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: HistoryActions,
    zone: ZoneId,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverHighlight()
            .clickable(onClick = onToggle)
            .handCursor()
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutcomeBadge(record.outcome)
            Text(
                text = HistoryFormat.summary(record, zone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showConnection && connection != null) {
                ColorSwatch(connection.config.color)
                Text(
                    text = connection.config.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                EnvironmentBadge(connection.config.environment)
            }
        }

        Text(
            text = if (expanded) record.statement else HistoryFormat.preview(record.statement),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "history-statement" },
        )

        if (!expanded) return@Column

        record.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "history-error" },
            )
        }
        EntryActions(record, actions)
    }
}

/** What can be done with the expanded entry, and what cannot be, with the reason. */
@Composable
private fun EntryActions(record: ExecutionRecord, actions: HistoryActions) {
    val sameConnection = record.connectionId == actions.editorConnection
    val open = actions.openInEditor.takeIf { sameConnection }
    val openTab = actions.openInNewTab.takeIf { sameConnection }

    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(
            text = "Open in new tab",
            onClick = { openTab?.invoke(record) },
            description = "history-open-tab",
            enabled = openTab != null,
            emphasis = ToolEmphasis.PRIMARY,
        )
        ToolButton(
            text = "Open in this tab",
            onClick = { open?.invoke(record) },
            description = "history-open",
            enabled = open != null,
        )
        ToolButton(
            text = "Copy",
            onClick = { actions.copy(record.statement) },
            description = "history-copy",
        )
        if (open == null) {
            Text(
                // Named rather than merely refused. "Open the connection this ran on"
                // is an instruction; a greyed-out button on its own is a puzzle.
                text = "Open the connection this ran on to put it back in a tab.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The end of the loaded pages, and the control that reads the one behind them. */
@Composable
private fun ShowMore(model: HistoryViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(
            text = "Show older",
            onClick = model::loadMore,
            description = "history-more",
            enabled = !model.loadingMore,
        )
        if (model.loadingMore) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * Nothing to show, in whichever of the three ways that happened.
 *
 * A search that matched nothing, an outcome filter with no entries under it, and a
 * connection nothing has been run against are three different situations. Only the
 * last is about there being no history; telling a user who has just typed a search
 * term that their queries will appear here once they run some is how a panel teaches
 * someone to stop reading it.
 *
 * A connection filter is deliberately *not* one of the narrowing cases. "No history
 * for this server yet" is the same fact as "no history yet" from where the user is
 * standing, and it deserves the sentence that says where history is kept rather than
 * an invitation to change a filter they did not set.
 */
@Composable
private fun EmptyList(model: HistoryViewModel) {
    when {
        model.search.isNotBlank() -> EmptyState(
            title = "No match in what is loaded",
            detail = "Nothing among the entries read so far contains that. " +
                "Show older reads further back.",
            description = "history-empty-search",
        )

        model.outcome != null -> EmptyState(
            title = "Nothing ${HistoryFormat.outcome(model.outcome!!)}",
            detail = "No execution here ended that way. Choose Any outcome to see the rest.",
            description = "history-empty-filtered",
        )

        else -> EmptyState(
            title = "No history yet",
            detail = "Statements you run are recorded here — on this machine, in the same " +
                "owner-only file as your connections, and nowhere else.",
            description = "history-empty",
        )
    }
}

@Composable
private fun Loading() {
    Box(
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "history-loading" },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}

/**
 * Clearing is deliberate, and the dialog says exactly how much goes.
 *
 * It also says what does *not* go, which is the half a user cannot check afterwards:
 * the connections stay. The cascade runs the other way — deleting a connection takes
 * its history with it — and someone who has just been asked "clear all history?" has
 * every reason to wonder which of the two this is.
 */
@Composable
fun ClearHistoryConfirmation(
    scope: HistoryScope,
    name: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val whose = when (scope) {
        HistoryScope.Everything -> "every connection"
        is HistoryScope.OneConnection -> name?.let { "\"$it\"" } ?: "this connection"
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Clear the history for $whose?") },
        text = {
            Text(
                "The statements recorded for $whose are deleted from this machine. " +
                    "The connections themselves are not touched. This cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "confirm-clear-history" },
            ) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "cancel-clear-history" },
            ) {
                Text("Cancel")
            }
        },
        modifier = Modifier.semantics { contentDescription = "clear-history-confirmation" },
    )
}
