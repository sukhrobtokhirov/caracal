package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Environment

/**
 * The connection sidebar, grouped by environment with production at the top.
 *
 * The ordering is the store's, not this file's: burying a production connection at
 * the bottom of an alphabetical list is exactly the accident the environment tag
 * exists to prevent.
 */
@Composable
fun ConnectionList(
    connections: List<ConnectionView>,
    selectedId: ConnectionId?,
    enabled: Boolean,
    onSelect: (ConnectionId) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PaneHeader(title = "Connections") {
            ToolButton(
                text = "New",
                onClick = onCreate,
                description = "new-connection",
                enabled = enabled,
                emphasis = ToolEmphasis.PRIMARY,
            )
        }
        Hairline()

        if (connections.isEmpty()) {
            Text(
                "No connections yet. Choose New to add a PostgreSQL or Redis server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(Space.lg)
                    .semantics { contentDescription = "connections-empty" },
            )
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
                        onSelect = { onSelect(view.id) },
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
        Text(
            environment.wire.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (environment == Environment.PROD) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
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
@Composable
private fun ConnectionRow(view: ConnectionView, selected: Boolean, onSelect: () -> Unit) {
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The stripe fills the row's height, and a row in a lazy list is measured
            // against an unbounded one until something asks for the intrinsic.
            .height(IntrinsicSize.Min)
            .background(background)
            .hoverHighlight()
            .clickable(onClick = onSelect)
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
                EngineBadge(view.config.engine)
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
    }
}
