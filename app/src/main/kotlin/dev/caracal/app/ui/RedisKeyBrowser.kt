package dev.caracal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caracal.app.FocusRequest
import dev.caracal.app.KeyRow
import dev.caracal.app.RedisBrowserViewModel
import dev.caracal.app.RedisFormat
import dev.caracal.app.ScanProgress
import dev.caracal.core.redis.KeyType
import dev.caracal.core.redis.RedisKey
import dev.caracal.core.redis.ScanStop

/**
 * The Redis key browser.
 *
 * Everything on this pane is an answer to the same question §3.4 keeps asking: what
 * did the server actually say? The list is the keys `SCAN` returned and nothing else,
 * the tree is those keys arranged by their own names, the counts on a group are keys
 * *on this page*, and the line at the bottom says which of the traversal's endings
 * this one had — because "complete" and "the budget ran out" look identical if all you
 * draw is a list that stopped.
 *
 * The tree in particular reads exactly one thing: [RedisBrowserViewModel.rows], which
 * is [dev.caracal.app.RedisKeyTree] over keys already in hand. Expanding a group cannot
 * issue a command, because there is no command here to issue.
 */
@Composable
fun RedisKeyBrowser(
    model: RedisBrowserViewModel,
    onOpenKey: (RedisKey) -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequest = remember { FocusRequest() },
) {
    Column(modifier = modifier.fillMaxSize().testTag("redis-browser")) {
        PaneHeader(title = "Keys", glyph = Glyphs.KEYS) {
            ToolButton(
                text = if (model.grouped) "Grouped" else "Flat",
                onClick = model::toggleGrouping,
                tag = "keys-grouping",
                // Lit while grouping is on: it is a mode the pane is in, not a button
                // that was pressed once.
                emphasis = if (model.grouped) ToolEmphasis.PRIMARY else ToolEmphasis.NORMAL,
            )
            ToolButton(text = "Refresh", onClick = model::refresh, tag = "keys-refresh")
        }
        Hairline()
        Filters(model, focus)
        Hairline()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val rows = model.rows
            when {
                model.connectionId == null -> BrowserMessage(
                    "Open a Redis connection to browse it.",
                    "keys-idle",
                )

                rows.isEmpty() && model.scanning -> BrowserMessage("Scanning…", "keys-scanning")

                rows.isEmpty() -> EmptyKeyspace(model)

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
                        KeyRowLine(
                            row = row,
                            selected = row.key != null && row.key == model.selected,
                            onToggle = { model.toggle(row.path) },
                            onOpen = {
                                row.key?.let {
                                    model.select(it)
                                    onOpenKey(it)
                                }
                            },
                        )
                    }
                }
            }
        }

        model.failure?.let { failure ->
            ErrorBanner(
                failure = failure,
                onDismiss = model::dismissFailure,
                modifier = Modifier.padding(Space.md),
            )
        }
        Hairline()
        ScanFooter(model)
    }
}

/**
 * The pattern, the type, and what the tree is grouped by.
 *
 * The pattern is applied on Enter rather than as it is typed, and that is not
 * politeness about keystrokes: applying it per character would start a fresh bounded
 * traversal of the keyspace for every letter of `user:*`.
 */
@Composable
private fun Filters(model: RedisBrowserViewModel, focus: FocusRequest) {
    val patternHere = remember { FocusRequester() }
    // §4.4's focus-the-search chord, and §4.5's "move focus to the browser" after a
    // switch. Both arrive as a request rather than a call, because the pane they mean
    // is not always on screen when the decision is taken.
    LaunchedEffect(focus.pending) { if (focus.consume()) patternHere.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Caracal.colors.paneHeader)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineField(
                value = model.pattern,
                onValueChange = model::edit,
                tag = "keys-pattern",
                placeholder = "user:*",
                focus = patternHere,
                onSubmit = model::search,
                modifier = Modifier.weight(1f),
            )
            ToolButton(
                text = "Search",
                onClick = model::search,
                tag = "keys-search",
                enabled = !model.scanning,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuButton(
                text = "Type: ${model.typeFilter?.wire ?: "all"}",
                tag = "keys-type",
                actions = buildList {
                    add(
                        MenuAction(
                            label = "All types",
                            tag = "keys-type-all",
                            onClick = { model.filterBy(null) },
                        ),
                    )
                    KeyType.entries.forEach { type ->
                        add(
                            MenuAction(
                                label = type.wire,
                                tag = "keys-type-${type.wire}",
                                onClick = { model.filterBy(type) },
                            ),
                        )
                    }
                },
            )
            if (model.grouped) {
                ToolButton(
                    text = "Expand",
                    onClick = model::expandAll,
                    tag = "keys-expand-all",
                )
                ToolButton(
                    text = "Collapse",
                    onClick = model::collapseAll,
                    tag = "keys-collapse-all",
                )
            }
        }
        // The glob is Redis's own, and saying so is cheaper than a support question:
        // a user who types a regular expression here gets no keys and no explanation.
        Text(
            text = "Patterns are Redis globs: user:*, session:??, *:tmp.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("keys-glob-hint"),
        )
    }
}

/**
 * One line: a group of keys, or a key.
 *
 * The label a group shows is a segment of a name and the label a key shows may be
 * only its last segment, and neither can be turned back into a key — which is
 * §3.4's rule, kept by construction rather than by care. [KeyRow.key] carries the
 * bytes, and it is the only thing this hands to the caller.
 */
@Composable
private fun KeyRowLine(
    row: KeyRow,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.treeRow)
            .background(if (selected) Caracal.colors.stripe else Color.Transparent)
            .hoverHighlight()
            .clickable { if (row.expandable) onToggle() else onOpen() }
            .handCursor()
            // The same four keys the schema tree answers. A keyspace and a catalog
            // are nothing alike except in this: they are both trees, and a tree is
            // walked with arrows.
            .arrowsWalkTree(row.expandable, row.expanded, onToggle)
            .expansion(row.expandable, row.expanded, onToggle)
            .semantics { this.selected = selected }
            .testTag(row.describe()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same accent stripe the connection list marks its selection with, and in
        // the same place. A key open in the value pane is selected in exactly the sense
        // a connection is.
        Box(
            modifier = Modifier
                .width(Sizes.selectionStripe)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = Space.md + (row.depth * 12).dp, end = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupDisclosure(row = row, onToggle = onToggle)
            // A grouping is a folder; a key is what it holds. The type badge further
            // along the row still spells the word out — this is the version of it the
            // eye can read while scrolling, and it is absent exactly when the type is,
            // which is when the server was never asked.
            Glyph(
                when {
                    row.expandable -> if (row.expanded) Glyphs.SCHEMA_OPEN else Glyphs.SCHEMA
                    else -> row.metadata?.type?.let { Glyphs.of(it) } ?: Glyphs.KEYS
                },
            )
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (row.expandable) FontFamily.Default else FontFamily.Monospace,
                color = if (row.expandable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                // Clipped at the end rather than the middle: a name too long for the
                // pane is still selected by clicking it, and the value pane beside it
                // shows the whole thing.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.expandable) {
                // Keys under this grouping *on this page*. The server was never asked
                // how many there are in total, so this does not claim to know.
                Text(
                    text = row.childCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Row
            }
            row.metadata?.let { metadata ->
                metadata.type?.let { KeyTypeBadge(it) }
                RedisFormat.ttlShort(metadata.ttl)?.let { RowDetail(it, "key-ttl") }
                RedisFormat.memoryShort(metadata.memory)?.let { RowDetail(it, "key-memory") }
            }
        }
    }
}

@Composable
private fun RowDetail(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.testTag(tag),
    )
}

/** The triangle, with a leaf keeping the square so the names stay in one column. */
@Composable
private fun GroupDisclosure(row: KeyRow, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(Sizes.disclosure)
            .then(
                if (!row.expandable) {
                    Modifier
                } else {
                    Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .hoverHighlight(MaterialTheme.shapes.extraSmall)
                        .clickable(role = Role.Button, onClick = onToggle)
                        .handCursor()
                        .testTag("${row.describe()}-toggle")
                        .semantics {
                            contentDescription = if (row.expanded) "Collapse" else "Expand"
                        }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!row.expandable) return@Box
        Text(
            text = if (row.expanded) "▾" else "▸",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The filters in force, in one phrase.
 *
 * Both are named when both are set, because either one on its own is a complete
 * explanation of an empty result and the user cannot tell which from the keys that
 * are not there.
 */
private fun describeFilters(model: RedisBrowserViewModel): String = listOfNotNull(
    model.appliedPattern.takeIf { it.isNotBlank() },
    model.typeFilter?.let { "type ${it.wire}" },
).joinToString(" and ")

/**
 * Nothing came back — which is three different situations.
 *
 * A traversal that finished having matched nothing is a fact about the keyspace. One
 * that stopped on a budget without matching anything is a fact about the *budget*, and
 * telling a user their pattern found nothing when the scan simply has not reached the
 * keys yet is how a browser teaches someone that it cannot be trusted.
 */
@Composable
private fun EmptyKeyspace(model: RedisBrowserViewModel) {
    when (model.progress) {
        is ScanProgress.More -> EmptyState(
            title = "No keys yet.",
            detail = "The scan has walked part of the keyspace without matching " +
                (if (model.filtered) describeFilters(model) else "anything") +
                ". Load more to continue from where it stopped.",
            tag = "keys-empty-partial",
            action = {
                ToolButton(
                    text = "Load more",
                    onClick = model::loadMore,
                    tag = "keys-load-more-empty",
                    emphasis = ToolEmphasis.PRIMARY,
                )
            },
        )

        ScanProgress.Complete -> EmptyState(
            title = "No keys match.",
            // §4.7: the pattern and the type are what the user has to change, so both
            // are named. Saying "nothing matched" without repeating the filters is
            // asking someone to remember what they typed four panes ago.
            detail = if (model.filtered) {
                "The whole keyspace was scanned and nothing matched ${describeFilters(model)}."
            } else {
                "The whole keyspace was scanned. This database is empty."
            },
            tag = "keys-empty-complete",
            action = if (model.filtered) {
                {
                    ToolButton(
                        text = "Clear filters",
                        onClick = model::clearFilters,
                        tag = "keys-clear-filters",
                        emphasis = ToolEmphasis.PRIMARY,
                    )
                }
            } else {
                null
            },
        )

        else -> BrowserMessage("Scanning…", "keys-scanning")
    }
}

/**
 * Where the traversal stopped, and the sentence §3.4 asks to be said out loud.
 *
 * The snapshot warning is permanent rather than a one-time notice. It is not a caveat
 * about this build — it is how `SCAN` works, it applies to every page on screen, and a
 * user comparing two refreshes needs it in front of them at the moment they notice a
 * key they expected is missing.
 */
@Composable
private fun ScanFooter(model: RedisBrowserViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Caracal.colors.paneHeader)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (model.scanning) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(10.dp).testTag("keys-busy"),
                )
            }
            Text(
                text = model.progressLine(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("keys-progress"),
            )
            if (model.hasMore) {
                ToolButton(
                    text = "Load more",
                    onClick = model::loadMore,
                    tag = "keys-load-more",
                    enabled = !model.scanning,
                    emphasis = ToolEmphasis.PRIMARY,
                )
            }
        }
        Text(
            text = "Browsing is not a snapshot: keys created or deleted while it runs " +
                "can be missed or repeated.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.testTag("keys-snapshot-warning"),
        )
    }
}

/** What the footer says, in the terms the traversal ended on. */
private fun RedisBrowserViewModel.progressLine(): String {
    val found = "${keys.size} ${if (keys.size == 1) "key" else "keys"}"
    return when (val progress = progress) {
        ScanProgress.Idle -> "Nothing scanned yet."
        is ScanProgress.Scanning -> if (progress.pages <= 1) {
            "Scanning…"
        } else {
            // The count is what makes an empty MATCH legible: something is happening,
            // it is bounded, and this is how far it has got.
            "Scanning — ${progress.pages} pages, $iterations SCAN calls so far."
        }

        ScanProgress.Complete -> "$found. Traversal complete."
        // Not "there is more" — there is, and saying so beside a button that would
        // collect nothing is worse than saying nothing. What has to change is the
        // pattern.
        ScanProgress.Full ->
            "$found — as many as this browser holds. Narrow the pattern to see further."
        is ScanProgress.More -> when (progress.stopped) {
            ScanStop.PAGE_FULL -> "$found. The page filled; there is more."
            ScanStop.ITERATION_BUDGET ->
                "$found. Stopped after $iterations SCAN calls; there is more."

            ScanStop.TIME_BUDGET -> "$found. Stopped on the time budget; there is more."
            ScanStop.COMPLETE, null -> "$found. The traversal did not finish."
        }
    }
}

@Composable
private fun BrowserMessage(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Space.lg).testTag(tag),
    )
}

/**
 * A stable name for one row, for the accessibility tree and for tests.
 *
 * Built from the label, which is lossy — two binary keys with the same clipped preview
 * describe the same. That is the same trade [dev.caracal.app.RedisKeyTree] already makes
 * for its paths, and it is safe for the same reason: nothing is ever reconstructed
 * from it.
 */
private fun KeyRow.describe(): String =
    if (expandable) "redis-group-$label" else "redis-key-$label"
