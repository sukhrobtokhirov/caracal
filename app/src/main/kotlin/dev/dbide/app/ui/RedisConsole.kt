package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dbide.app.ConsoleEntry
import dev.dbide.app.ParsedLine
import dev.dbide.app.RedisConsoleViewModel
import dev.dbide.app.RedisFormat
import dev.dbide.core.redis.Elision
import dev.dbide.core.redis.RedisReply

/**
 * The raw command console.
 *
 * It looks like `redis-cli` on purpose — the same tokenizer, the same escapes, the
 * same reply notation — because the audience already has that syntax in their fingers
 * and a console that disagreed subtly with the one they know would be worse than one
 * with no escapes at all.
 *
 * Two things it does that `redis-cli` does not. It shows the *parse* before it runs a
 * line whose quoting is ambiguous, so an argument boundary is settled before a command
 * is sent rather than after it has done something. And it refuses commands: the guard
 * in `:core` decides, this pane draws the answer, and a dangerous command produces a
 * question rather than a result.
 *
 * The transcript is memory only. §3.9's reason is one line: a Redis command's arguments
 * are where its secrets are, and a console history on disk would be a file of them.
 */
@Composable
fun RedisConsole(model: RedisConsoleViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().semantics { contentDescription = "redis-console" }) {
        PaneHeader(title = "Console") {
            ToolButton(
                text = "Clear",
                onClick = model::clearHistory,
                description = "console-clear",
                enabled = model.entries.isNotEmpty(),
            )
        }
        Hairline()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (model.entries.isEmpty()) {
                EmptyState(
                    title = "Nothing run yet",
                    detail = "Type a command — PING, GET user:42, TTL session:abc. " +
                        "Dangerous commands are blocked until you say otherwise.",
                    description = "console-empty",
                )
            } else {
                Transcript(model.entries)
            }
        }

        Hairline()
        Preview(model.parsed)
        Prompt(model)
    }

    model.pending?.let { pending ->
        CommandConfirmation(
            pending = pending,
            onConfirm = model::confirm,
            onCancel = model::cancelConfirmation,
        )
    }
}

/** What has been run this session, oldest at the top, following the newest command. */
@Composable
private fun Transcript(entries: List<ConsoleEntry>) {
    val scroll = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) scroll.scrollToItem(entries.lastIndex)
    }

    SelectionContainer {
        LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "console-transcript" },
            contentPadding = PaddingValues(vertical = Space.sm),
        ) {
            items(entries, key = { it.sequence }) { entry -> Entry(entry) }
        }
    }
}

@Composable
private fun Entry(entry: ConsoleEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.sm)
            .semantics { contentDescription = "console-entry-${entry.sequence}" },
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // The command's name and never its arguments. The line that was typed
                // is in the recall history and nowhere else; this is what a screenshot
                // of the transcript is allowed to contain.
                text = "> ${entry.label}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            entry.result?.let {
                Text(
                    text = RedisFormat.duration(it.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        entry.failure?.let { failure ->
            Text(
                text = failure.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "console-failure" },
            )
        }

        entry.result?.let { result ->
            result.reply.lines().forEach { line -> ReplyLine(line) }
            if (result.truncated) {
                Text(
                    text = "The reply was larger than this build keeps; part of it is not shown.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "console-truncated" },
                )
            }
        }
    }
}

@Composable
private fun ReplyLine(line: Line) {
    Text(
        text = line.text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = when (line.kind) {
            LineKind.ERROR -> MaterialTheme.colorScheme.error
            LineKind.ELIDED -> MaterialTheme.colorScheme.onSurfaceVariant
            LineKind.VALUE -> MaterialTheme.colorScheme.onSurface
        },
        maxLines = 8,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = (line.depth * 14).dp),
    )
}

/**
 * The parse, shown before the line runs when its quoting could be read two ways.
 *
 * §3.9 asks for this and it is the console's one genuinely protective feature: `SET k
 * "a b"` is two arguments or three depending on whose tokenizer you ask, and the moment
 * to find out which this one thinks is before the key is written, not after.
 */
@Composable
private fun Preview(parsed: ParsedLine) {
    when (parsed) {
        ParsedLine.Empty -> Unit

        is ParsedLine.Invalid -> Text(
            text = parsed.message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .background(Dbide.colors.paneHeader)
                .padding(horizontal = Space.lg, vertical = Space.sm)
                .semantics { contentDescription = "console-parse-error" },
        )

        is ParsedLine.Ready -> if (parsed.ambiguous) {
            Text(
                text = "Sends " + parsed.arguments.joinToString(" ") { "[${RedisFormat.oneLine(it, 40)}]" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Dbide.colors.paneHeader)
                    .padding(horizontal = Space.lg, vertical = Space.sm)
                    .semantics { contentDescription = "console-parse-preview" },
            )
        }
    }
}

/** The command line, and the sentence about what this console does not keep. */
@Composable
private fun Prompt(model: RedisConsoleViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Dbide.colors.paneHeader)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ">",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            InlineField(
                value = model.line,
                onValueChange = model::edit,
                description = "console-input",
                // Not an example command: a placeholder that reads as a command is a
                // placeholder someone tries to run, and one that looks identical to a
                // line already typed.
                placeholder = "type a command, then Enter",
                enabled = !model.running,
                onSubmit = model::run,
                // The arrows walk the session's own history, which is the gesture
                // every shell has and the only reason the lines are kept at all.
                onKey = { event ->
                    when (event.key) {
                        Key.DirectionUp -> {
                            model.recallEarlier()
                            true
                        }

                        Key.DirectionDown -> {
                            model.recallLater()
                            true
                        }

                        else -> false
                    }
                },
                modifier = Modifier.weight(1f),
            )
            if (model.running) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(10.dp).semantics { contentDescription = "console-busy" },
                )
            }
            ToolButton(
                text = "Run",
                onClick = model::run,
                description = "console-run",
                enabled = model.runnable,
                emphasis = ToolEmphasis.PRIMARY,
            )
        }
        Text(
            text = "History is kept in memory for this session only — a command's " +
                "arguments can be a password, and nothing here is written to disk.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.semantics { contentDescription = "console-privacy-note" },
        )
    }
}

/** One rendered line of a reply. */
private data class Line(val depth: Int, val text: String, val kind: LineKind)

private enum class LineKind { VALUE, ERROR, ELIDED }

/**
 * A reply, flattened into lines the way `redis-cli` prints one.
 *
 * The depth is already bounded by `:core` — a reply deeper than
 * [dev.dbide.core.redis.RedisLimits.replyDepth] arrives with an [RedisReply.Elided]
 * where the rest of it was — so this walk cannot run away. [MAX_LINES] is the second
 * bound, on what one entry may occupy in a transcript that keeps two hundred of them.
 */
private fun RedisReply.lines(): List<Line> = buildList {
    fun walk(reply: RedisReply, depth: Int, label: String?) {
        if (size >= MAX_LINES) return
        val prefix = label?.let { "$it " }.orEmpty()
        when (reply) {
            RedisReply.Nil -> add(Line(depth, "$prefix(nil)", LineKind.VALUE))
            is RedisReply.Integer -> add(Line(depth, "$prefix(integer) ${reply.value}", LineKind.VALUE))
            is RedisReply.Decimal -> add(Line(depth, "$prefix(double) ${reply.value}", LineKind.VALUE))
            is RedisReply.Bool -> add(Line(depth, "$prefix(${reply.value})", LineKind.VALUE))
            is RedisReply.Status -> add(Line(depth, "$prefix${reply.value}", LineKind.VALUE))
            is RedisReply.Bulk -> add(
                Line(depth, prefix + RedisFormat.oneLine(reply.value, 200), LineKind.VALUE),
            )

            // A nested error is a value, not the command's failure: `EXEC` returns an
            // array with one inside when one queued command failed and the rest did not.
            is RedisReply.Failure -> add(Line(depth, "$prefix(error) ${reply.message}", LineKind.ERROR))

            is RedisReply.Elided -> add(Line(depth, "$prefix… ${reply.reason.said()}", LineKind.ELIDED))

            is RedisReply.Items -> {
                if (reply.items.isEmpty()) {
                    add(Line(depth, "$prefix(empty ${reply.kind.name.lowercase()})", LineKind.VALUE))
                    return
                }
                if (label != null) add(Line(depth, "$prefix(${reply.kind.name.lowercase()})", LineKind.VALUE))
                reply.items.forEachIndexed { index, item ->
                    walk(item, if (label == null) depth else depth + 1, "${index + 1})")
                }
                if (reply.truncated) {
                    add(Line(depth + 1, "… more elements than this build keeps", LineKind.ELIDED))
                }
            }
        }
    }
    walk(this@lines, 0, null)
    if (size >= MAX_LINES) add(Line(0, "… the rest of this reply is not shown", LineKind.ELIDED))
}

private fun Elision.said(): String = message

/** Lines one transcript entry may occupy. A console is not a pager. */
private const val MAX_LINES = 200
