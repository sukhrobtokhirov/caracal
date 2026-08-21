package dev.dbide.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
// Aliased: the same three names exist for a key event, and both kinds are read here.
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as pointerMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed as pointerShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dbide.app.ColumnWindow
import dev.dbide.app.GridText
import dev.dbide.app.JsonFormat
import dev.dbide.app.ResultGridState
import dev.dbide.app.SelectionGesture
import dev.dbide.app.columnWindow
import dev.dbide.app.rightAligned
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column as ResultColumn
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.Notice
import java.awt.Cursor

private val ROW_HEIGHT = Sizes.gridRow
private val HEADER_HEIGHT = Sizes.gridHeader
private val SCROLLBAR_WIDTH = 12.dp
private val CELL_PADDING = Space.md

/**
 * The result grid.
 *
 * Compose has no data grid, so this is the one in plan §5.9, built where `LazyColumn`
 * stops. It virtualizes both ways: rows through `LazyColumn`, and columns through
 * [columnWindow], which composes only the columns the viewport intersects and leaves
 * spacers where the rest would be. A fifty-column result therefore costs the eight
 * columns on screen, not fifty, per visible row.
 *
 * The header and every row share one [ResultGridState.horizontal] scroll state, which
 * is what keeps them aligned; the row-number gutter sits outside that scroll, so it
 * stays put while the columns move under it.
 *
 * Every value is drawn as text and never as anything a database could make executable.
 * The grid is read-only in v0.1, deliberately: nothing here writes.
 */
@Composable
fun ResultGrid(
    state: ResultGridState,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = state.result
    val focus = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Focusable, so that the copy chord has somewhere to land before the user
            // has clicked anything.
            .focusRequester(focus)
            .focusable()
            // Preview, so the chord is the grid's before it is a cell's. Meta is the
            // macOS chord and Ctrl is everywhere else's; both are accepted on both.
            .onPreviewKeyEvent { event ->
                val copying = event.type == KeyEventType.KeyDown &&
                    event.key == Key.C &&
                    (event.isCtrlPressed || event.isMetaPressed)
                if (!copying) return@onPreviewKeyEvent false
                state.copyText()?.let(onCopy)
                true
            }
            .testTag("result-grid"),
    ) {
        if (result.columns.isEmpty()) {
            CommandResult(state, modifier = Modifier.weight(1f))
        } else {
            Box(modifier = Modifier.weight(1f)) { Table(state, focus) }
            if (state.panelOpen) {
                Hairline()
                ValuePanel(state, onCopy)
            }
        }
        if (state.noticesOpen && result.notices.isNotEmpty()) {
            Hairline()
            NoticePanel(state)
        }
        Hairline()
        StatusBar(state, onCopy)
    }
}

/** The header, the rows, and the two scrollbars that say where in them you are. */
@Composable
private fun Table(state: ResultGridState, focus: FocusRequester) {
    val density = LocalDensity.current
    val cellStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()

    // Column widths are sampled in characters, which is a property of the data; only
    // here is there a measured font to turn characters into dp.
    val advance = remember(cellStyle, density) {
        with(density) { measurer.measure("0", cellStyle).size.width.toDp().value }
    }
    LaunchedEffect(state, advance) { state.fitColumns(advance) }

    // Wide enough for the largest row number this result can show, and no wider.
    val gutter = remember(state, advance) {
        ((state.result.rows.size.toString().length + 1) * advance + 12).dp
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Known before the children compose, so the first frame already draws the right
        // columns rather than all of them.
        val viewport = (maxWidth - gutter - SCROLLBAR_WIDTH).value
        val window by remember(state, viewport, density) {
            derivedStateOf {
                val scrolled = with(density) { state.horizontal.value.toDp().value }
                columnWindow(state.widths.toList(), scrolled, viewport)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            HeaderRow(state, window, gutter)
            Hairline()

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(state = state.vertical, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.result.rows, key = { index, _ -> index }) { index, cells ->
                        GridRow(state, index, cells, window, gutter, cellStyle, focus)
                    }
                }
                if (state.result.rows.isEmpty()) {
                    // The columns are still overhead, and they are the useful half of
                    // this answer: the query was right, the table has nothing in it.
                    EmptyState(
                        title = "No rows.",
                        detail = "The statement ran and matched nothing.",
                        tag = "grid-empty",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(state.vertical),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }

            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(state.horizontal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The sticky header: sticky because it is outside the `LazyColumn` entirely, and
 * horizontally scrolled by the same state as the rows below it.
 */
@Composable
private fun HeaderRow(state: ResultGridState, window: ColumnWindow, gutter: Dp) {
    Row(
        modifier = Modifier
            .height(HEADER_HEIGHT)
            .fillMaxWidth()
            .background(Dbide.colors.paneHeader),
    ) {
        Box(modifier = Modifier.width(gutter).fillMaxHeight())
        VerticalHairline()
        Row(modifier = Modifier.weight(1f).horizontalScroll(state.horizontal)) {
            Spacer(modifier = Modifier.width(window.leading.dp))
            for (index in window.range) {
                HeaderCell(state, index, state.result.columns[index])
            }
            Spacer(modifier = Modifier.width(window.trailing.dp))
        }
    }
}

/** One column's name over its PostgreSQL type, with a divider that can be dragged. */
@Composable
private fun HeaderCell(state: ResultGridState, index: Int, column: ResultColumn) {
    Box(modifier = Modifier.width(state.widths[index].dp).fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = CELL_PADDING, vertical = 4.dp)
                .semantics(mergeDescendants = true) { testTag = "grid-header-$index" },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = column.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // PostgreSQL's own name for the type, not the JDBC approximation of it.
                text = column.typeName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(7.dp)
                .fillMaxHeight()
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(index) {
                    detectHorizontalDragGestures { change, amount ->
                        change.consume()
                        state.resize(index, amount.toDp().value)
                    }
                }
                .testTag("grid-resize-$index"),
        ) {
            VerticalHairline(modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** One row: its number in the frozen gutter, its cells in the scrolling region. */
@Composable
private fun GridRow(
    state: ResultGridState,
    index: Int,
    cells: List<CellValue>,
    window: ColumnWindow,
    gutter: Dp,
    cellStyle: TextStyle,
    focus: FocusRequester,
) {
    val selected = index in state.selectedRows
    // Alternating rows, because forty columns of monospace at 24dp is exactly the
    // shape in which the eye loses which row it was on halfway across.
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        index % 2 == 1 -> Dbide.colors.stripe
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .height(ROW_HEIGHT)
            .fillMaxWidth()
            .background(background)
            .hoverHighlight()
            // How far through the result this row is, said once per row rather than
            // once per cell. The count is what the grid actually holds — a result
            // stopped at a limit says so in its own status line, and repeating that
            // ten thousand times here would not help anyone.
            .semantics {
                collectionInfo = CollectionInfo(
                    rowCount = state.result.rows.size,
                    columnCount = state.result.columns.size,
                )
            },
    ) {
        RowNumber(state, index, gutter, selected, focus)
        VerticalHairline()
        Row(modifier = Modifier.weight(1f).horizontalScroll(state.horizontal)) {
            Spacer(modifier = Modifier.width(window.leading.dp))
            for (column in window.range) {
                GridCell(
                    state = state,
                    row = index,
                    column = column,
                    value = cells.getOrElse(column) { CellValue.Null },
                    style = cellStyle,
                    focus = focus,
                )
            }
            Spacer(modifier = Modifier.width(window.trailing.dp))
        }
    }
}

/**
 * The row number, which is not one of the returned columns.
 *
 * Clicking it selects the row — plain, with Ctrl/Cmd to add one, with Shift to extend
 * — because a copy of whole rows is a different thing from a copy of one cell, and the
 * two need different gestures to ask for.
 */
@Composable
private fun RowNumber(
    state: ResultGridState,
    index: Int,
    gutter: Dp,
    selected: Boolean,
    focus: FocusRequester,
) {
    Box(
        modifier = Modifier
            .width(gutter)
            .fillMaxHeight()
            // A raw pointer handler rather than a click, because which selection a
            // click means is carried by the keyboard modifiers a click does not report.
            .pointerInput(index) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) {
                            state.selectRow(index, event.keyboardModifiers.gesture())
                            focus.grab()
                        }
                    }
                }
            }
            .padding(horizontal = CELL_PADDING)
            .testTag("grid-row-$index"),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/**
 * Takes the keyboard, so the copy chord lands on the grid from here on.
 *
 * Only ever in response to a click. A result arriving must not take focus on its own:
 * the user was typing in the editor that produced it, and a caret that jumps out of
 * the script on every run makes the editor unusable.
 */
private fun FocusRequester.grab() {
    runCatching { requestFocus() }
}

/** Shift extends the selection, Ctrl or Cmd adds to it, and a bare click replaces it. */
private fun PointerKeyboardModifiers.gesture(): SelectionGesture = when {
    pointerShiftPressed -> SelectionGesture.EXTEND
    pointerCtrlPressed || pointerMetaPressed -> SelectionGesture.TOGGLE
    else -> SelectionGesture.REPLACE
}

/**
 * One cell: a bounded single line of text, and never anything else.
 *
 * `NULL` and the empty string are the two values a blank cell could mean, so both are
 * drawn as dimmed italic placeholders that differ from each other and from any value
 * that renders those characters itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    state: ResultGridState,
    row: Int,
    column: Int,
    value: CellValue,
    style: TextStyle,
    focus: FocusRequester,
) {
    val focused = state.focused?.row == row && state.focused?.column == column
    val format = state.result.columns[column].format
    val placeholder = GridText.isPlaceholder(value)

    Box(
        modifier = Modifier
            .width(state.widths[column].dp)
            .fillMaxHeight()
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            // An outline as well as a fill. One cell in ten thousand is a small
            // target, and the fill alone is a shade difference at the size of a word.
            .then(
                if (focused) {
                    Modifier.border(Sizes.hairline, MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = {
                    state.focus(row, column)
                    focus.grab()
                },
                onDoubleClick = {
                    state.focus(row, column)
                    focus.grab()
                    state.openPanel()
                },
            )
            .padding(horizontal = CELL_PADDING)
            .testTag("grid-cell-$row-$column")
            // §4.9's row and column context. A cell read on its own is a value with
            // no subject — "42" tells you nothing about which of forty columns it
            // came from, and the header is scrolled somewhere off to the left. The
            // name travels with the value instead.
            //
            // `collectionItemInfo` carries the same fact in the structured form the
            // platform bridges prefer, where they support it; the sentence is the
            // part that works everywhere.
            .semantics {
                contentDescription =
                    "${state.result.columns[column].name}, ${GridText.preview(value)}"
                collectionItemInfo = CollectionItemInfo(
                    rowIndex = row,
                    rowSpan = 1,
                    columnIndex = column,
                    columnSpan = 1,
                )
            },
        contentAlignment = if (format.rightAligned) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text = GridText.preview(value),
            style = style,
            fontStyle = if (placeholder) FontStyle.Italic else FontStyle.Normal,
            color = if (placeholder) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = if (format.rightAligned) TextAlign.End else TextAlign.Start,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The whole of the focused value, which the one-line cell could only preview.
 *
 * A JSON column can be reformatted here for reading. The reformatting is a view: what
 * a copy takes is the value as the server sent it, indentation and all.
 */
@Composable
private fun ValuePanel(state: ResultGridState, onCopy: (String) -> Unit) {
    val column = state.focusedColumn()
    val value = state.focusedValue()
    if (column == null || value == null) return

    val raw = GridText.copy(value)
    val pretty = column.format == ColumnFormat.JSON && state.prettyJson
    val shown = if (pretty) JsonFormat.pretty(raw) ?: raw else raw

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp, max = 240.dp)
            .testTag("grid-detail"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dbide.colors.paneHeader)
                .padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${column.name} · ${column.typeName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (column.format == ColumnFormat.JSON) {
                ToolButton(
                    text = if (pretty) "Raw" else "Pretty",
                    onClick = state::togglePrettyJson,
                    tag = "grid-detail-json",
                )
            }
            ToolButton(
                text = "Copy value",
                onClick = { onCopy(raw) },
                tag = "grid-detail-copy",
            )
            ToolButton(text = "Close", onClick = state::closePanel, tag = "grid-detail-close")
        }

        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = shown,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.md)
                    .testTag("grid-detail-value"),
            )
        }

        // What is on screen is what was retained. A value the server cut short must not
        // look complete just because a panel is big enough to hold it.
        remnant(value)?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Space.lg, vertical = Space.sm)
                    .testTag("grid-detail-truncated"),
            )
        }
    }
}

/** What a truncated value is not showing, said plainly. `null` when nothing is missing. */
private fun remnant(value: CellValue): String? = when {
    value is CellValue.Text && value.truncated ->
        "Truncated for display; the rest of the value stayed on the server."

    value is CellValue.Binary && value.truncated ->
        "Preview of ${value.byteCount} bytes; the rest of the value stayed on the server."

    else -> null
}

/**
 * What the server said, on a statement that did not fail.
 *
 * PostgreSQL's notices are the half of its output an application is free to throw
 * away and a user is not: a `RAISE NOTICE` is somebody deliberately reporting
 * something, and the statement that carries one often carries nothing else. So they
 * are drawn where the result is, in the server's own words, rather than summarized
 * into a count.
 *
 * Bounded and scrolled, because a loop can raise one per iteration and a panel that
 * grew with them would push the grid off the screen. `:core` caps how many are kept;
 * this caps how much room they take.
 */
@Composable
private fun NoticePanel(state: ResultGridState) {
    val notices = state.result.notices

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 132.dp)
            .testTag("grid-notices"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dbide.colors.paneHeader)
                .padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (notices.size == 1) "The server sent a notice" else "The server sent ${notices.size} notices",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolButton(text = "Close", onClick = state::toggleNotices, tag = "grid-notices-close")
        }

        SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                notices.forEachIndexed { index, notice -> NoticeLine(index, notice) }
            }
        }
    }
}

/**
 * One notice: what it said, then what it added.
 *
 * The severity leads because it is the difference between `NOTICE` and `WARNING`,
 * and PostgreSQL's own clients put it there. Detail and hint follow indented and
 * only when present — a hint is frequently the whole answer, which is the same
 * reason [ErrorBanner] carries them.
 */
@Composable
private fun NoticeLine(index: Int, notice: Notice) {
    Column(
        // Merged: severity, message, detail, and hint are one thing the user reads and
        // one thing a screen reader should announce, not four adjacent fragments.
        modifier = Modifier.semantics(mergeDescendants = true) {
            testTag = "grid-notice-$index"
        },
    ) {
        Text(
            text = listOfNotNull(notice.severity, notice.message).joinToString(": "),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
        listOfNotNull(
            notice.detail?.let { "Detail: $it" },
            notice.hint?.let { "Hint: $it" },
        ).forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.lg),
            )
        }
    }
}

/** Duration, row counts, truncation, and the copy the keyboard would also perform. */
@Composable
private fun StatusBar(state: ResultGridState, onCopy: (String) -> Unit) {
    val copyable = state.copyText()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Dbide.colors.paneHeader)
            .padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = GridText.status(state.result),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("grid-status"),
        )
        state.noticeLabel()?.let { label ->
            ToolButton(
                text = label,
                onClick = state::toggleNotices,
                tag = "grid-toggle-notices",
            )
        }
        if (state.result.columns.isNotEmpty()) {
            ToolButton(
                text = if (state.panelOpen) "Hide value" else "Show value",
                onClick = { if (state.panelOpen) state.closePanel() else state.openPanel() },
                tag = "grid-toggle-panel",
                enabled = state.focused != null,
            )
        }
        ToolButton(
            text = state.copyLabel(),
            onClick = { copyable?.let(onCopy) },
            tag = "grid-copy",
            enabled = copyable != null,
        )
    }
}

/**
 * A statement that returned no columns.
 *
 * pgjdbc surfaces the affected count but not PostgreSQL's command tag, so this says
 * what was actually received rather than inventing `UPDATE 3` from a keyword.
 *
 * When the server sent notices, they are what the statement produced, and saying
 * "there is nothing to show" above a panel full of them would be wrong.
 */
@Composable
private fun CommandResult(state: ResultGridState, modifier: Modifier = Modifier) {
    val spoke = state.result.notices.isNotEmpty()
    EmptyState(
        title = "Statement completed.",
        detail = if (spoke) {
            "It returned no columns. What the server said about it is below."
        } else {
            "It returned no columns, so there is no grid to show. " +
                "The status line below carries what the server reported."
        },
        tag = "grid-command",
        modifier = modifier.fillMaxWidth(),
    )
}
