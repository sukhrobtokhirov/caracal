package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Connections", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick = onCreate,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "new-connection" },
            ) {
                Text("New")
            }
        }
        HorizontalDivider()

        if (connections.isEmpty()) {
            Text(
                "No connections yet. Choose New to add a PostgreSQL or Redis server.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(12.dp)
                    .semantics { contentDescription = "connections-empty" },
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            Environment.entries.sortedBy { it.severity }.forEach { environment ->
                val group = connections.filter { it.config.environment == environment }
                if (group.isEmpty()) return@forEach

                item(key = "header-${environment.wire}") {
                    Text(
                        environment.wire.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                    )
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

@Composable
private fun ConnectionRow(view: ConnectionView, selected: Boolean, onSelect: () -> Unit) {
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = "connection-${view.config.name}" },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorSwatch(view.config.color)
            Text(
                view.config.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EngineBadge(view.config.engine)
            // Production and read-only travel with the row itself, not just the
            // detail pane: the list is where the wrong click happens.
            if (view.config.environment == Environment.PROD) EnvironmentBadge(Environment.PROD)
            if (view.config.readOnly) ReadOnlyBadge()
            StatusBadge(view.runtime)
        }
        Text(
            "${view.config.host}:${view.config.port}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
