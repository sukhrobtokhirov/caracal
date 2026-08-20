package dev.dbide.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        Hairline()

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

        Hairline()
        // The hint sits on the header's own shade, which is what makes it a footer
        // rather than one more line of the tree.
        Text(
            text = inserted?.let { "Inserted $it" }
                ?: "Double-click a name to insert it into the editor as quoted SQL.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(Dbide.colors.paneHeader)
                .padding(horizontal = Space.lg, vertical = Space.md)
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
    PaneHeader(title = "Database") {
        ToolButton(
            text = if (showSystemSchemas) "Hide system" else "System",
            onClick = onToggleSystem,
            description = "tree-system-schemas",
            // Lit while the system schemas are showing, because that is a mode the
            // tree is in and not merely a button that was pressed once.
            emphasis = if (showSystemSchemas) ToolEmphasis.PRIMARY else ToolEmphasis.NORMAL,
        )
        ToolButton(text = "Refresh", onClick = onRefresh, description = "tree-refresh")
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
    val indent = Space.md + (row.depth * 14).dp

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizes.treeRow)
                .hoverHighlight()
                .combinedClickable(
                    onClick = { if (row.expandable) onToggle() },
                    // The plan's "explicit action or double-click". There is no editor
                    // to insert into yet, so what the double-click does is supplied by
                    // the caller; what it produces is already the right text.
                    onDoubleClick = onInsert,
                )
                .handCursor()
                .padding(start = indent, end = Space.md)
                .semantics { contentDescription = row.key.describe() },
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Disclosure(
                expandable = row.expandable,
                expanded = row.expanded,
                description = "${row.key.describe()}-toggle",
                onToggle = onToggle,
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
                modifier = Modifier.padding(start = indent + Space.xl, end = Space.md),
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
                ToolButton(text = "Retry", onClick = onRefresh, description = "node-retry")
            }
        }

        row.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = indent + Space.xl, end = Space.md, bottom = Space.xs)
                    .semantics { contentDescription = "node-note" },
            )
        }
    }
}

/**
 * The triangle that opens a node.
 *
 * A target rather than a glyph. The arrow was 10dp of text with nothing clickable
 * around it, which made the row the only real way to open a schema — and the row is
 * also what a double-click inserts from, so the two gestures were competing for the
 * same pixels. Given its own square, the arrow can be aimed at: it lights up under
 * the pointer, it swallows the click, and the row underneath keeps its double-click.
 *
 * A leaf still occupies the square. Columns would otherwise sit a few pixels left of
 * the tables above them, and a tree whose indentation depends on what a row happens
 * to be is a tree that looks bent.
 */
@Composable
private fun Disclosure(
    expandable: Boolean,
    expanded: Boolean,
    description: String,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Sizes.disclosure)
            .then(
                if (!expandable) {
                    Modifier
                } else {
                    Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .hoverHighlight(MaterialTheme.shapes.extraSmall)
                        .clickable(onClick = onToggle)
                        .handCursor()
                        .semantics { contentDescription = description }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!expandable) return@Box
        Text(
            text = if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.bodyMedium,
            // Dimmer than the name beside it: the triangle is a control, and the
            // name is the thing the user is actually scanning for.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `PK`, `system`, `not null` — what a node is, in the tree's own shorthand. */
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
            .padding(horizontal = Space.sm),
    )
}

@Composable
private fun TreeMessage(text: String, description: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Space.lg).semantics { contentDescription = description },
    )
}

@Composable
private fun TreeFailure(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = "tree-error" },
        )
        ToolButton(text = "Retry", onClick = onRetry, description = "tree-retry")
    }
}

/** A stable name for one node, for the accessibility tree and for tests. */
private fun NodeKey.describe(): String = when (this) {
    is NodeKey.Schema -> "node-schema-$schema"
    is NodeKey.Folder -> "node-folder-$schema-${kind.name.lowercase()}"
    is NodeKey.Relation -> "node-object-$schema-$name"
    is NodeKey.Column -> "node-column-$schema-$relation-$ordinal"
}
