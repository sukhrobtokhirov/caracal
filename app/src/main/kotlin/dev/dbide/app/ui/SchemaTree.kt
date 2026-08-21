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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.dbide.app.NodeKey
import dev.dbide.app.NodeState
import dev.dbide.app.RowKind
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.TreeRow

/**
 * Where a node's text can be sent.
 *
 * Two destinations rather than one lambda per menu line, because the menu's lines
 * differ in the *text* and not in where it goes: a name, a qualified name, and a
 * whole `SELECT` all end up in the same editor. Which text belongs to which line is
 * this file's business; where the editor and the clipboard are is the shell's.
 */
class TreeActions(
    /** Puts SQL into the editor at the caret, and brings the editor forward. */
    val insert: (String) -> Unit,
    val copy: (String) -> Unit,
)

/**
 * The object browser.
 *
 * Everything on screen was asked for: a node's children are read when it is opened,
 * and a node that could not be read says so on its own line. The tree is never
 * replaced by an error, because the schema whose permissions are wrong is usually
 * not the one being worked in.
 *
 * A row answers the three desktop gestures the sidebar's rows already answer: click
 * opens it, double-click inserts its name, right-click offers the rest. The menu is
 * where the things with no other affordance live — the query a table is usually
 * wanted for, the clipboard, and a re-read of one node rather than the whole tree.
 */
@Composable
fun SchemaTree(
    model: SchemaTreeViewModel,
    actions: TreeActions,
    modifier: Modifier = Modifier,
) {
    // What the last gesture did, for the footer. One line for all of them: they
    // answer the same question — "did that land?" — and the pane is 320dp wide.
    var echo: String? by remember(model.connectionId) { mutableStateOf(null) }

    Column(modifier = modifier.fillMaxSize().testTag("schema-tree")) {
        TreeHeader(
            showSystemSchemas = model.showSystemSchemas,
            reloading = model.reloading,
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
                                            actions.insert(it)
                                            echo = "Inserted $it"
                                        }
                                    },
                                    onCopy = {
                                        row.identifier?.let {
                                            actions.copy(it)
                                            echo = "Copied $it"
                                        }
                                    },
                                    // The statement, not the result: a menu line that
                                    // sent a query to production the moment it was
                                    // clicked would be a menu people learn to avoid.
                                    // It lands in the editor, where Run is a decision.
                                    onSelectRows = {
                                        row.identifier?.let {
                                            actions.insert("SELECT * FROM $it LIMIT 100;")
                                            echo = "Inserted a query over $it"
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
            text = echo
                ?: "Double-click a name to insert it as quoted SQL. Right-click for the rest.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(Dbide.colors.paneHeader)
                .padding(horizontal = Space.lg, vertical = Space.md)
                .testTag("tree-hint"),
        )
    }
}

@Composable
private fun TreeHeader(
    showSystemSchemas: Boolean,
    reloading: Boolean,
    onToggleSystem: () -> Unit,
    onRefresh: () -> Unit,
) {
    PaneHeader(title = "Database", glyph = Glyphs.DATABASE) {
        ToolButton(
            text = if (showSystemSchemas) "Hide system" else "System",
            onClick = onToggleSystem,
            tag = "tree-system-schemas",
            // Lit while the system schemas are showing, because that is a mode the
            // tree is in and not merely a button that was pressed once.
            emphasis = if (showSystemSchemas) ToolEmphasis.PRIMARY else ToolEmphasis.NORMAL,
        )
        // §4.7: the tree below is the previous listing, still perfectly usable, so
        // the only place the re-read is visible is here. A disabled Refresh that did
        // not say why would look like a broken one.
        ToolButton(
            text = if (reloading) "Refreshing…" else "Refresh",
            onClick = onRefresh,
            tag = "tree-refresh",
            enabled = !reloading,
        )
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
    onCopy: () -> Unit,
    onSelectRows: () -> Unit,
) {
    val indent = Space.md + (row.depth * 14).dp

    var menuOpen by remember(row.key) { mutableStateOf(false) }
    var menuAt by remember(row.key) { mutableStateOf(DpOffset.Zero) }
    // The menu hangs off the whole node — the row and whatever error or note is under
    // it — and Material places it below its anchor, so opening it under the pointer
    // means subtracting the anchor's height back off. Measured rather than assumed:
    // the row is a fixed 26dp, but a schema that could not be read is taller.
    var height by remember(row.key) { mutableStateOf(0) }
    val density = LocalDensity.current

    Column(modifier = Modifier.fillMaxWidth().onSizeChanged { height = it.height }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizes.treeRow)
                // The row the menu belongs to stays lit while the menu is open. The
                // tree has no selection to fall back on, and a menu of five lines
                // floating over forty identical monospace names is otherwise a menu
                // with no visible subject.
                .background(if (menuOpen) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .hoverHighlight()
                .combinedClickable(
                    onClick = { if (row.expandable) onToggle() },
                    // The plan's "explicit action or double-click". There is no editor
                    // to insert into yet, so what the double-click does is supplied by
                    // the caller; what it produces is already the right text.
                    onDoubleClick = onInsert,
                )
                .onSecondaryClick { position ->
                    // `position` is already in the whole row's coordinates — the
                    // gesture sits outside the indent padding — so the offset is the
                    // pointer as it was, less the anchor Material is measuring from.
                    menuAt = with(density) {
                        DpOffset(position.x.toDp(), position.y.toDp() - height.toDp())
                    }
                    menuOpen = true
                }
                .handCursor()
                .padding(start = indent, end = Space.md)
                .testTag(row.key.describe()),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Disclosure(
                expandable = row.expandable,
                expanded = row.expanded,
                tag = "${row.key.describe()}-toggle",
                onToggle = onToggle,
            )
            // What this row is, before its name is read. A tree of forty monospace
            // identifiers all look alike until something distinguishes a table from
            // the view beside it, and the flag the row already carries is enough to
            // give a primary key its own mark rather than a column's.
            Glyph(
                if (row.flags.contains("PK")) {
                    Glyphs.PRIMARY_KEY
                } else {
                    Glyphs.of(row.kind, row.expanded)
                },
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
                    modifier = Modifier.size(10.dp).testTag("node-loading"),
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
                        testTag = "node-error"
                    },
                )
                ToolButton(text = "Retry", onClick = onRefresh, tag = "node-retry")
            }
        }

        row.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = indent + Space.xl, end = Space.md, bottom = Space.xs)
                    .testTag("node-note"),
            )
        }

        ContextMenu(
            expanded = menuOpen,
            at = menuAt,
            onDismiss = { menuOpen = false },
            tag = "node-menu",
            actions = nodeActions(row, onRefresh, onInsert, onCopy, onSelectRows),
        )
    }
}

/**
 * The menu one node offers.
 *
 * Built from the row rather than from a kind-by-kind table, because the row already
 * knows the two things the menu turns on: whether it names something insertable, and
 * whether it has children to re-read. A folder has neither an identifier nor a name
 * worth quoting, and it gets one line; a column has a name and no children, and it
 * gets two.
 *
 * The order is what the row is most often wanted for. On a table that is the query —
 * "show me what is in this" is the reason the tree was opened at all — and the two
 * clipboard-shaped lines follow it, with the re-read last because it is maintenance
 * rather than work. No line here is destructive, so nothing is set behind a rule.
 */
private fun nodeActions(
    row: TreeRow,
    onRefresh: () -> Unit,
    onInsert: () -> Unit,
    onCopy: () -> Unit,
    onSelectRows: () -> Unit,
): List<MenuAction> = buildList {
    val queryable = row.kind == RowKind.TABLE ||
        row.kind == RowKind.VIEW ||
        row.kind == RowKind.MATERIALIZED_VIEW
    // A hundred rows, because the point of the line is to see the shape of the data
    // and an unbounded SELECT against a table the tree just estimated at nine million
    // rows is a mistake the menu would be making on the user's behalf.
    if (queryable) add(MenuAction("Select 100 rows", "menu-select-rows") { onSelectRows() })
    if (row.identifier != null) {
        // The same text for both, and the same text a double-click produces: quoted,
        // and qualified when the node has a schema to qualify with. A menu that
        // copied a bare name would be a second vocabulary for one identifier.
        add(MenuAction("Insert name", "menu-insert") { onInsert() })
        add(MenuAction("Copy name", "menu-copy") { onCopy() })
    }
    // Only where there is something under the node to re-read. A column has nothing,
    // and a function has no children this version draws, so neither offers it.
    if (row.expandable) {
        add(
            MenuAction(
                label = if (queryable) "Refresh columns" else "Refresh",
                tag = "menu-refresh",
            ) { onRefresh() },
        )
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
    tag: String,
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
                        .testTag(tag)
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
private fun TreeMessage(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Space.lg).testTag(tag),
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
            modifier = Modifier.testTag("tree-error"),
        )
        ToolButton(text = "Retry", onClick = onRetry, tag = "tree-retry")
    }
}

/** A stable name for one node, for the accessibility tree and for tests. */
private fun NodeKey.describe(): String = when (this) {
    is NodeKey.Schema -> "node-schema-$schema"
    is NodeKey.Folder -> "node-folder-$schema-${kind.name.lowercase()}"
    is NodeKey.Relation -> "node-object-$schema-$name"
    is NodeKey.Column -> "node-column-$schema-$relation-$ordinal"
}
