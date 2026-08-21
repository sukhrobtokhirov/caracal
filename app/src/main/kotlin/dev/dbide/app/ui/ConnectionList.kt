package dev.dbide.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment

/**
 * Everything the sidebar can do to one connection.
 *
 * Bundled rather than passed one lambda at a time, because they travel together:
 * the right-click menu offers all of them, and a caller that can do one of them can
 * do the rest.
 */
class ConnectionActions(
    /** Open it, or — when it is already open — bring its editor forward. */
    val activate: (ConnectionView) -> Unit,
    val close: (ConnectionView) -> Unit,
    val test: (ConnectionView) -> Unit,
    val edit: (ConnectionView) -> Unit,
    val delete: (ConnectionView) -> Unit,
)

/**
 * The connection sidebar, grouped by environment with production at the top.
 *
 * The ordering is the store's, not this file's: burying a production connection at
 * the bottom of an alphabetical list is exactly the accident the environment tag
 * exists to prevent.
 *
 * A row is a real desktop row: single click selects it, double click opens it, and
 * right click offers everything the detail pane offers. The detail pane is still
 * there and still correct — but reaching across the window to press Open on a list
 * you are already pointing at is a trip the pointer should not have to make.
 */
@Composable
fun ConnectionList(
    connections: List<ConnectionView>,
    selectedId: ConnectionId?,
    enabled: Boolean,
    onSelect: (ConnectionId) -> Unit,
    onCreate: () -> Unit,
    actions: ConnectionActions,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(title = "Connections", glyph = Glyphs.CONNECTIONS) {
            ToolButton(
                text = "New",
                onClick = onCreate,
                description = "new-connection",
                enabled = enabled,
                emphasis = ToolEmphasis.PRIMARY,
            )
            // Collapsing from the pane itself, as well as from the shell. The button
            // that hides something should be on the thing being hidden; the one that
            // brings it back cannot be, which is why there are two of them.
            ToolButton(text = "Hide", onClick = onCollapse, description = "collapse-sidebar")
        }
        Hairline()

        if (connections.isEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Space.lg),
                modifier = Modifier.padding(Space.lg),
            ) {
                Text(
                    "No connections yet. Choose New to add a PostgreSQL or Redis server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "connections-empty" },
                )
                // The two marks the New window opens on. An empty sidebar is the one
                // place with room to say what the application can talk to.
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    Engine.entries.forEach { engine -> EngineTile(engine, size = 30.dp) }
                }
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            Environment.entries.sortedBy { it.severity }.forEach { environment ->
                val group = connections.filter { it.config.environment == environment }
                if (group.isEmpty()) return@forEach

                item(key = "header-${environment.wire}") {
                    GroupHeader(environment, group.size)
                }
                items(group, key = { it.id.value }) { view ->
                    ConnectionRow(
                        view = view,
                        selected = view.id == selectedId,
                        enabled = enabled,
                        onSelect = { onSelect(view.id) },
                        actions = actions,
                    )
                }
            }
        }
    }
}

/**
 * One environment's heading, with how many are under it.
 *
 * The count is not decoration: it is what tells someone scrolling past that the
 * `PROD` group has four connections in it and not the one they can currently see.
 */
@Composable
private fun GroupHeader(environment: Environment, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Glyphs.of(environment))
            Text(
                environment.wire.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (environment == Environment.PROD) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * One connection: what it is called, what it is, and whether it is open.
 *
 * Two lines rather than three. The sidebar is the pane most worth being narrow, and
 * the host is the line a reader only needs when two connections share a name — so it
 * shares its line with the badges rather than claiming one of its own.
 *
 * Selection is a stripe down the leading edge as well as a filled background. The
 * background alone is a colour difference, and a colour difference is the thing that
 * disappears on a projector and for a colour-blind reader.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionRow(
    view: ConnectionView,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    actions: ConnectionActions,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

    var menuOpen by remember(view.id) { mutableStateOf(false) }
    var menuAt by remember(view.id) { mutableStateOf(DpOffset.Zero) }
    // The menu is anchored to the row and placed below it, so opening it under the
    // pointer means measuring the row and subtracting its height back off.
    var height by remember(view.id) { mutableStateOf(0) }
    val density = LocalDensity.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The stripe fills the row's height, and a row in a lazy list is measured
            // against an unbounded one until something asks for the intrinsic.
            .height(IntrinsicSize.Min)
            .onSizeChanged { height = it.height }
            .background(background)
            .hoverHighlight()
            .combinedClickable(
                onClick = onSelect,
                // What a double-click means everywhere else a list of servers is
                // shown: connect to it. Selecting first, because the pane behind the
                // list should be describing the row that is being opened.
                onDoubleClick = {
                    onSelect()
                    if (enabled) actions.activate(view)
                },
            )
            .onSecondaryClick { position ->
                // Right-clicking selects too. A menu that acts on one connection while
                // the rest of the window describes another is how the wrong database
                // gets dropped.
                onSelect()
                menuAt = with(density) {
                    DpOffset(position.x.toDp(), position.y.toDp() - height.toDp())
                }
                menuOpen = true
            }
            .handCursor()
            .semantics { contentDescription = "connection-${view.config.name}" },
    ) {
        Box(
            modifier = Modifier
                .width(Sizes.selectionStripe)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.xs),
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // The engine's mark, where the row starts. It replaces the outlined
                // POSTGRES badge that used to sit on the second line: the badge was a
                // word being read to answer a question the eye can answer from a
                // shape, and it took a third of the width the host needs. It carries
                // the badge's own content description, so nothing announces less.
                EngineLogo(view.config.engine, size = 14.dp, described = true)
                ColorSwatch(view.config.color)
                // The name takes everything the status does not. A second weighted
                // child here would halve it, and half a sidebar is not enough for
                // "payments-prod".
                Text(
                    view.config.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The word as well as the dot, here as everywhere: the list is read
                // at a glance, and "is that one open?" is the glance it is read for.
                StatusBadge(view.runtime)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Production and read-only travel with the row itself, not just the
                // detail pane: the list is where the wrong click happens.
                if (view.config.environment == Environment.PROD) EnvironmentBadge(Environment.PROD)
                if (view.config.readOnly) ReadOnlyBadge()
                Text(
                    "${view.config.host}:${view.config.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ContextMenu(
            expanded = menuOpen,
            at = menuAt,
            onDismiss = { menuOpen = false },
            description = "connection-menu",
            actions = rowActions(view, enabled, actions),
        )
    }
}

/**
 * The menu one connection offers.
 *
 * The same five things the detail pane offers, in the same order and with the same
 * words — a menu that renames Open to Connect is a second vocabulary for one set of
 * actions. Every one of them is disabled while something is in flight, because the
 * view model would ignore the click anyway and a menu that silently does nothing is
 * worse than one that shows why.
 */
private fun rowActions(
    view: ConnectionView,
    enabled: Boolean,
    actions: ConnectionActions,
): List<MenuAction> = buildList {
    if (view.runtime.isOpen) {
        add(
            MenuAction("Close", "menu-close", enabled) { actions.close(view) },
        )
    } else {
        add(
            MenuAction("Open", "menu-open", enabled) { actions.activate(view) },
        )
    }
    add(MenuAction("Test", "menu-test", enabled) { actions.test(view) })
    add(MenuAction("Edit…", "menu-edit", enabled) { actions.edit(view) })
    // Last, and behind a rule. The dialog is still what actually deletes anything.
    add(MenuAction("Delete…", "menu-delete", enabled, danger = true) { actions.delete(view) })
}
