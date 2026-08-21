package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.dbide.app.CloseReason
import dev.dbide.app.EditorTab
import dev.dbide.app.EditorTabs
import dev.dbide.app.FocusRequest
import dev.dbide.app.PendingClose
import dev.dbide.app.Shortcut
import dev.dbide.app.Shortcuts
import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId

/**
 * The SQL side of an open PostgreSQL connection: its tabs, and whichever one is in
 * front.
 *
 * The strip is scoped to [connection], which is what keeps the shell bar overhead,
 * the object browser alongside, and the editor in the middle all describing one
 * server. A tab still carries its own connection — [others] is what moving it to a
 * different one offers — and moving a tab takes the workspace with it through
 * [onMoved], because a tab that vanished from the strip it was in would look closed.
 */
@Composable
fun QueryWorkspace(
    tabs: EditorTabs,
    connection: ConnectionConfig,
    others: List<ConnectionConfig>,
    onMoved: (ConnectionId) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    shortcuts: Shortcuts = remember { Shortcuts() },
    focus: FocusRequest = remember { FocusRequest() },
) {
    val open = tabs.of(connection.id)
    val active = tabs.active(connection.id)

    Column(modifier = modifier.fillMaxSize()) {
        EditorTabStrip(
            tabs = open,
            active = active,
            shortcuts = shortcuts,
            onSelect = tabs::activate,
            onClose = tabs::requestClose,
            onNew = { tabs.open(connection) },
            menu = { tab -> tabActions(tabs, tab, others, onMoved) },
        )
        Hairline()
        if (active == null) {
            NoTabs(onNew = { tabs.open(connection) })
        } else {
            // Keyed on the tab, so each one keeps its own split and its own export
            // report. Without it, switching tabs would hand the tab arriving on screen
            // the state of the one leaving it.
            key(active.id) { QueryPane(active, onCopy, shortcuts = shortcuts, focus = focus) }
        }
    }

    tabs.closing?.let { pending ->
        CloseTabConfirmation(
            pending = pending,
            onConfirm = tabs::confirmClose,
            onCancel = tabs::cancelClose,
        )
    }
}

/**
 * What a tab can be asked to do to itself.
 *
 * Moving is offered per open connection rather than as a submenu of every saved one:
 * a tab can only run against a server this process has a client for, and a menu
 * entry that first opens a connection is a menu entry that dials production.
 */
private fun tabActions(
    tabs: EditorTabs,
    tab: EditorTab,
    others: List<ConnectionConfig>,
    onMoved: (ConnectionId) -> Unit,
): List<MenuAction> = buildList {
    add(
        MenuAction(
            label = "Duplicate",
            description = "tab-duplicate",
            onClick = { tabs.duplicate(tab) },
        ),
    )
    others.forEach { config ->
        add(
            MenuAction(
                label = "Move to ${config.name}",
                description = "tab-move-${config.name}",
                // The query on the server belongs to the connection that is running
                // it. Moving the tab out from under it would leave nothing to cancel.
                enabled = !tab.running,
                onClick = {
                    tabs.retarget(tab, config)
                    onMoved(config.id)
                },
            ),
        )
    }
    add(
        MenuAction(
            label = "Close",
            description = "tab-close",
            danger = true,
            onClick = { tabs.requestClose(tab) },
        ),
    )
}

/**
 * The tab strip.
 *
 * It scrolls rather than shrinking its tabs, per §4.3: eight tabs squeezed into a
 * strip is eight tabs called `sel…`, which is a strip that costs a click to read.
 * New sits outside the scroll, where it is always in the same place.
 */
@Composable
private fun EditorTabStrip(
    tabs: List<EditorTab>,
    active: EditorTab?,
    shortcuts: Shortcuts,
    onSelect: (EditorTab) -> Unit,
    onClose: (EditorTab) -> Unit,
    onNew: () -> Unit,
    menu: (EditorTab) -> List<MenuAction>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.paneHeader)
            .background(Dbide.colors.paneHeader)
            .semantics { contentDescription = "editor-tab-strip" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                EditorTabItem(
                    tab = tab,
                    index = index,
                    active = tab === active,
                    // Only the tab the chord would actually close names the chord.
                    closeTip = "${Shortcut.CLOSE_TAB.action}  ${shortcuts.chord(Shortcut.CLOSE_TAB)}"
                        .takeIf { tab === active },
                    onSelect = { onSelect(tab) },
                    onClose = { onClose(tab) },
                    actions = { menu(tab) },
                )
            }
        }
        VerticalHairline()
        ToolButton(
            text = "＋ New",
            onClick = onNew,
            description = "editor-tab-new",
            tooltip = "${Shortcut.NEW_TAB.action}  ${shortcuts.chord(Shortcut.NEW_TAB)}",
            modifier = Modifier.padding(horizontal = Space.sm),
        )
    }
}

/**
 * One tab: what is in it, whether it is busy, and whether closing it costs anything.
 *
 * The dot and the spinner occupy the same place, so the row never changes width when
 * a query starts — and they are the two things a person scanning a strip is actually
 * looking for: which of these is still running, and which of these have I not
 * finished writing.
 */
@Composable
private fun EditorTabItem(
    tab: EditorTab,
    index: Int,
    active: Boolean,
    closeTip: String?,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    actions: () -> List<MenuAction>,
) {
    var menu: DpOffset? by remember { mutableStateOf(null) }
    val accent = MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = 96.dp, max = 220.dp)
                .hoverHighlight()
                // Drawn rather than laid out, for the reason the workspace tabs above
                // are: an indicator that is a child has to fill the tab's width, and a
                // child filling the width is what decides it.
                .drawBehind {
                    if (!active) return@drawBehind
                    val thickness = 2.dp.toPx()
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, size.height - thickness),
                        size = Size(size.width, thickness),
                    )
                }
                .clickable(onClick = onSelect)
                .onSecondaryClick { at -> menu = DpOffset(at.x.dp, 0.dp) }
                .handCursor()
                .padding(start = Space.lg, end = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                tab.running -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(10.dp)
                        .semantics { contentDescription = "editor-tab-running-$index" },
                    strokeWidth = 1.5.dp,
                )

                tab.dirty -> Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .semantics { contentDescription = "editor-tab-unsaved-$index" },
                )
            }
            Text(
                text = tab.title,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { contentDescription = "editor-tab-$index" },
            )
            ToolButton(
                text = "✕",
                onClick = onClose,
                description = "editor-tab-close-$index",
                tooltip = closeTip,
            )
        }
        ContextMenu(
            expanded = menu != null,
            at = menu ?: DpOffset.Zero,
            actions = actions(),
            onDismiss = { menu = null },
            description = "editor-tab-menu-$index",
        )
    }
}

/** The connection is open and has no tabs, because the user closed the last one. */
@Composable
private fun NoTabs(onNew: () -> Unit) {
    EmptyState(
        title = "No query open",
        detail = "Open a tab to write SQL against this connection, or reopen a statement from history.",
        description = "query-no-tabs",
        action = {
            ToolButton(
                text = "New query",
                onClick = onNew,
                description = "query-new-tab",
                emphasis = ToolEmphasis.PRIMARY,
            )
        },
    )
}

/**
 * What closing this tab would cost, before it costs it.
 *
 * Two questions rather than one, because they have different answers: a statement on
 * the server has to be cancelled, and a script that is not saved anywhere is simply
 * gone. When both are true the running one is asked and the script is named in the
 * same breath — a dialog that says only "a query is running" and then also throws
 * away twenty lines of SQL is a dialog that lied by omission.
 */
@Composable
private fun CloseTabConfirmation(
    pending: PendingClose,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val running = pending.reason == CloseReason.RUNNING
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (running) "A statement is still running." else "Close \"${pending.tab.title}\"?",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text(
                    if (running) {
                        "Closing this tab cancels it on the server. Nothing will be left half-read."
                    } else {
                        "The script in this tab is not saved anywhere, and closing it " +
                            "cannot be undone."
                    },
                )
                if (running && pending.losesScript) {
                    Text(
                        "The script in it goes too — it is not saved anywhere.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "confirm-close-tab" },
            ) {
                Text(
                    text = if (running) "Cancel and close" else "Close",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "cancel-close-tab" },
            ) {
                Text(if (running) "Keep open" else "Keep it")
            }
        },
        modifier = Modifier.semantics { contentDescription = "close-tab-confirmation" },
    )
}

/**
 * The window is being closed and a tab holds a script.
 *
 * The last thing §4.3 asks for, and the one place the platform gives an application a
 * chance to ask: `⌘Q`, the close button, and the window menu all arrive here. It says
 * how many tabs rather than which, because the answer to "which" is behind the dialog.
 */
@Composable
fun QuitConfirmation(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Quit with unsaved scripts?") },
        text = {
            Text(
                "Query tabs are kept in memory only — deliberately, because a script " +
                    "can hold a password. Quitting now loses what is in them.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "confirm-quit" },
            ) {
                Text("Quit anyway", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "cancel-quit" },
            ) {
                Text("Keep working")
            }
        },
        modifier = Modifier.semantics { contentDescription = "quit-confirmation" },
    )
}
