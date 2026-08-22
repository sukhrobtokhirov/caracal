package dev.caracal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.caracal.app.LoadedValue
import dev.caracal.app.RedisFormat
import dev.caracal.app.RedisValueViewModel
import dev.caracal.app.TextView
import dev.caracal.app.ValueState
import dev.caracal.engine.api.FieldEntry
import dev.caracal.engine.api.KeyMetadata

/**
 * The value pane: one key, in a viewer suited to what it holds.
 *
 * §3.7 asks for a shared frame around six different bodies, and the frame is the part
 * that carries the promises. It says which key is open and shows the whole name rather
 * than the browser's clipped label; it says what the key is, how long it has left, and
 * roughly how large it is; it says how much of the value is on screen and how much is
 * not; and it distinguishes — always — between a collection that is empty and a key
 * that is gone.
 *
 * Nothing is ever loaded whole. Every body below reads pages, every footer says which
 * page it is on, and **Show more** is the only thing that reads another one.
 */
@Composable
fun RedisValueViewer(
    model: RedisValueViewModel,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag("redis-value")) {
        PaneHeader(title = "Value", glyph = Glyphs.VALUE) {
            val string = (model.state as? ValueState.Ready)?.value as? LoadedValue.Text
            if (string != null && model.json != null) {
                ToolButton(
                    text = "Raw",
                    onClick = model::showRaw,
                    tag = "value-raw",
                    emphasis = if (model.textView == TextView.RAW) {
                        ToolEmphasis.PRIMARY
                    } else {
                        ToolEmphasis.NORMAL
                    },
                )
                ToolButton(
                    text = "JSON",
                    onClick = model::showJson,
                    tag = "value-json",
                    emphasis = if (model.textView == TextView.JSON) {
                        ToolEmphasis.PRIMARY
                    } else {
                        ToolEmphasis.NORMAL
                    },
                )
            }
            if (model.key != null) {
                ToolButton(text = "Refresh", onClick = model::refresh, tag = "value-refresh")
            }
        }
        Hairline()

        when (val state = model.state) {
            ValueState.Idle -> EmptyState(
                title = "No key selected",
                detail = "Choose a key in the browser to read its value.",
                tag = "value-idle",
            )

            ValueState.Loading -> Loading()

            // A key that expired while it was open is Redis working, not this
            // application failing, and §3.5 asks for it to read that way.
            is ValueState.Missing -> EmptyState(
                title = "That key is not there.",
                detail = "It expired or was deleted. The rest of the browser is unaffected.",
                tag = "value-missing",
                action = {
                    ToolButton(
                        text = "Look again",
                        onClick = model::refresh,
                        tag = "value-retry",
                    )
                },
            )

            is ValueState.Unsupported -> EmptyState(
                title = "No viewer for a ${state.reported}.",
                detail = "This build reads strings, hashes, lists, sets, sorted sets, and streams.",
                tag = "value-unsupported",
            )

            is ValueState.Failed -> Box(
                modifier = Modifier.fillMaxSize().padding(Space.xl),
                contentAlignment = Alignment.TopCenter,
            ) {
                ErrorBanner(failure = state.failure, modifier = Modifier.fillMaxWidth())
            }

            is ValueState.Ready -> Column(modifier = Modifier.fillMaxSize()) {
                KeyHeader(state.metadata, onCopy = onCopy)
                model.notice?.let { notice ->
                    NoticeStrip(notice, onDismiss = model::dismissNotice)
                }
                Hairline()
                Box(modifier = Modifier.weight(1f)) {
                    SelectionContainer { ValueBody(state.value, model.textView, model.json) }
                }
                Hairline()
                ValueFooter(state, onMore = model::more, onCopy = onCopy)
            }
        }
    }
}

/**
 * The key, in full, with what is known about it.
 *
 * The whole name and not the browser's label: §3.4 truncates a name in a list and
 * requires it to stay copyable, and this is where it becomes copyable again. The name
 * is rendered as text and never as anything that could be interpreted — a Redis key
 * can contain anything at all, including a newline and a terminal escape.
 */
@Composable
private fun KeyHeader(metadata: KeyMetadata, onCopy: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Caracal.colors.paneHeader)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = RedisFormat.text(metadata.key.display),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("value-key-name"),
                )
            }
            // Only for a name that is text. There is no honest string form of a binary
            // key, and offering to copy one would put a lossy rendering on the
            // clipboard that finds nothing when it is pasted back.
            metadata.key.text?.let { name ->
                ToolButton(
                    text = "Copy key",
                    onClick = { onCopy(name) },
                    tag = "value-copy-key",
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            metadata.type?.let { KeyTypeBadge(it) }
            Detail(RedisFormat.ttl(metadata.ttl), "value-ttl")
            Detail(RedisFormat.memory(metadata.memory), "value-memory")
        }
    }
}

@Composable
private fun Detail(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}

/** Something that happened to the key while it was open, said once and dismissable. */
@Composable
private fun NoticeStrip(notice: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = notice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f).testTag("value-notice"),
        )
        ToolButton(text = "Dismiss", onClick = onDismiss, tag = "value-notice-dismiss")
    }
}

/** The six bodies. Which one is drawn is decided by the page's own type, never by a flag. */
@Composable
private fun ValueBody(value: LoadedValue, textView: TextView, json: String?) {
    when (value) {
        is LoadedValue.Text -> StringBody(value, textView, json)

        is LoadedValue.Fields -> PairTable(
            left = "field",
            right = "value",
            tag = "value-hash",
            rows = value.entries.size,
        ) { index ->
            val entry: FieldEntry = value.entries[index]
            PairRow(RedisFormat.text(entry.field), RedisFormat.text(entry.value), index)
        }

        is LoadedValue.Members -> PairTable(
            left = "member",
            right = "",
            tag = "value-set",
            rows = value.members.size,
        ) { index ->
            PairRow(RedisFormat.text(value.members[index]), null, index)
        }

        is LoadedValue.Scored -> PairTable(
            left = "member",
            // Redis's own formatting of the score, never a reparsed double: this is
            // the number the ordering is by, and it is being read because it matters.
            right = "score",
            tag = "value-zset",
            rows = value.members.size,
        ) { index ->
            val member = value.members[index]
            PairRow(RedisFormat.text(member.member), member.score, index)
        }

        is LoadedValue.Elements -> PairTable(
            left = "index",
            right = "value",
            tag = "value-list",
            rows = value.elements.size,
        ) { index ->
            val element = value.elements[index]
            // The list's own index and not the row number: a page starting at 200 is
            // showing elements 200 and up, and saying "1" would be a different claim.
            PairRow(element.index.toString(), RedisFormat.text(element.value), index)
        }

        is LoadedValue.Entries -> StreamBody(value)
    }
}

/**
 * A string, as text or as hexadecimal, with JSON as a third rendering of the first.
 *
 * The binary case is not a fallback and not an error: a Redis string is a byte
 * sequence, and a value that does not decode is shown as the bytes it is rather than
 * as a string of replacement characters that would differ from what the server holds.
 */
@Composable
private fun StringBody(value: LoadedValue.Text, textView: TextView, json: String?) {
    if (value.length == 0) {
        EmptyState(
            title = "Empty string.",
            detail = "The key is there and holds zero bytes.",
            tag = "value-empty-string",
        )
        return
    }

    // Remembered on the windows that produced it. The hex layout allocates a
    // `String` per byte pair and a list per line — for a 4 MB value that is millions
    // of objects — and it was being rebuilt on every recomposition of this pane,
    // including every drag inside the selection container around it.
    val shown = remember(value.windows, textView, json) {
        when {
            textView == TextView.JSON && json != null -> json
            value.binary -> value.hex.chunked(2).chunked(16).joinToString("\n") { it.joinToString(" ") }
            else -> value.text.orEmpty()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (value.binary) {
            Text(
                text = "This value is not UTF-8. It is shown as hexadecimal bytes.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.sm)
                    .testTag("value-binary"),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(Space.lg),
        ) {
            Text(
                text = shown,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics {
                    testTag = if (textView == TextView.JSON) "value-json-text" else "value-text"
                },
            )
        }
    }
}

/**
 * A stream: entries in the order they were appended, each with its fields.
 *
 * A list of pairs per entry rather than a map, because a stream entry may repeat a
 * field name and that is the one structure in Redis where the repetition is the point.
 */
@Composable
private fun StreamBody(value: LoadedValue.Entries) {
    if (value.entries.isEmpty()) {
        EmptyState(
            title = "Empty stream.",
            detail = "The key is there and holds no entries.",
            tag = "value-empty-stream",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("value-stream"),
        contentPadding = PaddingValues(vertical = Space.sm),
    ) {
        itemsIndexed(value.entries) { index, entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (index % 2 == 1) Caracal.colors.stripe else Color.Transparent)
                    .padding(horizontal = Space.lg, vertical = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(
                    // The ID as Redis wrote it. It is a millisecond and a sequence
                    // number joined by a hyphen, not a number, and reformatting it is
                    // how a continuation stops matching.
                    text = entry.id,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("stream-entry-$index"),
                )
                entry.fields.forEach { field ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        Cell(RedisFormat.text(field.field), Modifier.widthIn(min = 96.dp))
                        Cell(RedisFormat.text(field.value), Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * The table shape the four collection viewers share.
 *
 * Virtualized, because a page is a hundred rows and a session can page through
 * thousands of them without the pane ever holding more than it draws.
 */
@Composable
private fun PairTable(
    left: String,
    right: String,
    tag: String,
    rows: Int,
    row: @Composable (Int) -> Unit,
) {
    if (rows == 0) {
        EmptyState(
            title = "Empty collection.",
            detail = "The key is there and holds no entries.",
            tag = "value-empty-collection",
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Caracal.colors.paneHeader)
                .padding(horizontal = Space.lg, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(
                text = left,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(COLUMN_SPLIT),
            )
            if (right.isNotEmpty()) {
                Text(
                    text = right,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f - COLUMN_SPLIT),
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(tag),
        ) {
            items(rows) { index -> row(index) }
        }
    }
}

/** One row of a pair table. [right] is `null` for a set, which has only members. */
@Composable
private fun PairRow(left: String, right: String?, index: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (index % 2 == 1) Caracal.colors.stripe else Color.Transparent)
            .heightIn(min = Sizes.gridRow)
            .padding(horizontal = Space.lg, vertical = Space.xs)
            .testTag("value-row-$index"),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(left, Modifier.weight(if (right == null) 1f else COLUMN_SPLIT))
        if (right != null) Cell(right, Modifier.weight(1f - COLUMN_SPLIT))
    }
}

/**
 * One value in a table.
 *
 * Bounded to a few lines rather than one: a hash value is frequently a small JSON
 * document, and a single ellipsized line of it says nothing at all. Bounded rather
 * than unbounded because one 4 kB member must not be able to push every other row off
 * the screen.
 */
@Composable
private fun Cell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * How much of the value is on screen, how much is not, and how to see more.
 *
 * The three limits are said in different words because they are different facts: a
 * value with more pages to read, a page that dropped elements because a byte budget
 * ran out, and a string this build will not read past at all. Only the first has a
 * button.
 */
@Composable
private fun ValueFooter(state: ValueState.Ready, onMore: () -> Unit, onCopy: (String) -> Unit) {
    val value = state.value
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
            if (state.paging) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(10.dp).testTag("value-paging"),
                )
            }
            Text(
                text = value.extent(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("value-extent"),
            )
            // Gated on the label, not on the payload: reading `.text` here to decide
            // whether to draw a button materialised the whole value on every
            // recomposition of the footer. The text itself is read in the click.
            val copyable = (value as? LoadedValue.Text)?.takeIf { !it.binary }
            if (copyable != null) {
                ToolButton(
                    // The original text, never the reformatted JSON. §3.7 is explicit:
                    // a copy is of what Redis holds, not of what this pane drew.
                    text = "Copy value",
                    onClick = { copyable.text?.let(onCopy) },
                    tag = "value-copy",
                )
            }
            if (value.hasMore) {
                ToolButton(
                    text = "Show more",
                    onClick = onMore,
                    tag = "value-more",
                    enabled = !state.paging,
                    emphasis = ToolEmphasis.PRIMARY,
                )
            }
        }
        value.warning()?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("value-warning"),
            )
        }
        (value as? LoadedValue.Scored)?.let { RankingNote() }
        (value as? LoadedValue.Members)?.let { OrderNote() }
    }
}

/** How much has been read, in the unit the type is counted in. */
private fun LoadedValue.extent(): String = when (this) {
    is LoadedValue.Text ->
        "${RedisFormat.bytes(loadedBytes)} of ${RedisFormat.bytes(length)}"

    is LoadedValue.Fields ->
        "${entries.size} fields" + if (complete) " (all of them)" else " so far"

    is LoadedValue.Members ->
        "${members.size} members" + if (complete) " (all of them)" else " so far"

    is LoadedValue.Scored -> "${members.size} of ${RedisFormat.count(total)} by rank"
    is LoadedValue.Elements -> "${elements.size} of ${RedisFormat.count(total)} elements"
    is LoadedValue.Entries -> "${entries.size} entries" + if (complete) " (all of them)" else " so far"
}

/** A limit that was reached, in the words that say which one. */
private fun LoadedValue.warning(): String? {
    val capped = (this as? LoadedValue.Text)?.cappedAt
    return when {
        capped != null -> "This value is larger than ${RedisFormat.bytes(capped)}, " +
            "which is the most of one string this build will read."

        truncated -> "A page kept less than it read: some entries were larger than " +
            "the response budget allows."

        else -> null
    }
}

@Composable
private fun RankingNote() {
    Text(
        text = "Paged by rank. A member whose score changes between pages moves, " +
            "and so can be seen twice or missed.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.testTag("value-rank-note"),
    )
}

@Composable
private fun OrderNote() {
    Text(
        text = "Set members arrive in the server's own order, which means nothing " +
            "and can change.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.testTag("value-order-note"),
    )
}

@Composable
private fun Loading() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .testTag("value-loading"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text = "Reading…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.lg),
        )
    }
}

/** How a pair table divides its width between the two columns. */
private const val COLUMN_SPLIT = 0.34f
