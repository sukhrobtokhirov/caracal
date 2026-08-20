package dev.dbide.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dbide.app.NodeKey
import dev.dbide.app.NodeState
import dev.dbide.app.RowKind
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.TreeRow

/**
 * The object browser.
 *
 * Everything on screen was asked for: a node's children are read when it is opened,
 * and a node that could not be read says so on its own line. The tree is never
 * replaced by an error, because the schema whose permissions are wrong is usually
 * not the one being worked in.
 */
@Composable
fun SchemaTree(
    model: SchemaTreeViewModel,
    onInsertIdentifier: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var inserted: String? by remember(model.connectionId) { mutableStateOf(null) }

    Column(modifier = modifier.fillMaxSize().semantics { contentDescription = "schema-tree" }) {
        TreeHeader(
            showSystemSchemas = model.showSystemSchemas,
            onToggleSystem = model::toggleSystemSchemas,
            onRefresh = { model.refresh() },
        )
        HorizontalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val root = model.root) {
                null -> TreeMessage("Open a PostgreSQL connection to browse it.", "tree-idle")
                NodeState.Loading -> TreeMessage("Reading schemas…", "tree-loading")
                is NodeState.Failed -> TreeFailure(root.failure.message) { model.refresh() }
                is NodeState.Ready ->
                    if (root.items.isEmpty()) {
                        TreeMessage("No schemas are visible to this user.", "tree-empty")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(model.rows, key = { it.key.toString() }) { row ->
                                TreeNode(
                                    row = row,
                                    onToggle = { model.toggle(row.key) },
                                    onRefresh = { model.refresh(row.key) },
                                    onInsert = {
                                        row.identifier?.let {
                                            onInsertIdentifier(it)
                                            inserted = it
                                        }
                                    },
                                )
                            }
                        }
                    }
            }
        }

        HorizontalDivider()
        Text(
            text = inserted?.let { "Copied $it" } ?: "Double-click a name to copy it as quoted SQL.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics { contentDescription = "tree-hint" },
        )
    }
}

@Composable
private fun TreeHeader(
    showSystemSchemas: Boolean,
    onToggleSystem: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Database", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onToggleSystem,
                modifier = Modifier.semantics { contentDescription = "tree-system-schemas" },
            ) {
                Text(
                    if (showSystemSchemas) "Hide system" else "System",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.semantics { contentDescription = "tree-refresh" },
            ) {
                Text("Refresh", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * One line, and — when its children are loading, missing, or refused — one more
 * beneath it saying so.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeNode(
    row: TreeRow,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onInsert: () -> Unit,
) {
    val indent = 8.dp + (row.depth * 14).dp

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (row.expandable) onToggle() },
                    // The plan's "explicit action or double-click". There is no editor
                    // to insert into yet, so what the double-click does is supplied by
                    // the caller; what it produces is already the right text.
                    onDoubleClick = onInsert,
                )
                .padding(start = indent, end = 8.dp, top = 3.dp, bottom = 3.dp)
                .semantics { contentDescription = row.key.describe() },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (!row.expandable) " " else if (row.expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(10.dp),
            )
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (row.kind == RowKind.FOLDER) FontFamily.Default else FontFamily.Monospace,
                color = if (row.kind == RowKind.FOLDER) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            row.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.flags.forEach { flag -> TreeFlag(flag) }
            if (row.loading) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(10.dp).semantics { contentDescription = "node-loading" },
                )
            }
        }

        // A failure belongs to the node that failed. The rest of the tree is still
        // true, and replacing it would throw away everything the user had opened.
        row.error?.let { message ->
            Row(
                modifier = Modifier.padding(start = indent + 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f, fill = false).semantics {
                        contentDescription = "node-error"
                    },
                )
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.semantics { contentDescription = "node-retry" },
                ) {
                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        row.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = indent + 16.dp, end = 8.dp, bottom = 2.dp)
                    .semantics { contentDescription = "node-note" },
            )
        }
    }
}

@Composable
private fun TreeFlag(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 4.dp),
    )
}

@Composable
private fun TreeMessage(text: String, description: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(12.dp).semantics { contentDescription = description },
    )
}

@Composable
private fun TreeFailure(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = "tree-error" },
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = "tree-retry" },
        ) {
            Text("Retry")
        }
    }
}

/** A stable name for one node, for the accessibility tree and for tests. */
private fun NodeKey.describe(): String = when (this) {
    is NodeKey.Schema -> "node-schema-$schema"
    is NodeKey.Folder -> "node-folder-$schema-${kind.name.lowercase()}"
    is NodeKey.Relation -> "node-object-$schema-$name"
    is NodeKey.Column -> "node-column-$schema-$relation-$ordinal"
}
