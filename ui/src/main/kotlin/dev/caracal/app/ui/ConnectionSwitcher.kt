package dev.caracal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.caracal.app.ConnectionSearch
import dev.caracal.app.Shortcut
import dev.caracal.app.Shortcuts
import dev.caracal.core.connections.ConnectionView
import dev.caracal.core.connections.Environment

/**
 * §4.5's switcher: every saved connection, one line each, reachable from the
 * keyboard without the pointer ever being involved.
 *
 * A palette rather than an [AppDialog]. The other windows in this application are
 * places you go — history, settings, the connection form — and they are shaped like
 * it: a title, a rail, a footer, most of the window. This is a chord, three letters,
 * and Enter, and a control that is over in under a second should not open something
 * the size of a document.
 *
 * What it must not lose on the way is the safety context, which is exactly the risk
 * of a fast control: a switcher that reduced every server to a name would make
 * production one indistinguishable line among nine. So every row carries the same
 * marks the sidebar gives it — the colour, the engine, the environment in words, read
 * only, and whether there is a client open — and the production one is red here too.
 *
 * Choosing does not retarget anything. [onChoose] selects the connection and opens it
 * if it is closed; the tabs that were already pointed at other servers stay pointed at
 * them, because §4.3 made a tab's connection something only the tab's own menu
 * changes.
 */
@Composable
fun ConnectionSwitcher(
    connections: List<ConnectionView>,
    shortcuts: Shortcuts,
    onChoose: (ConnectionView) -> Unit,
    onDismiss: () -> Unit,
) {
    var query: String by remember { mutableStateOf("") }

    // Where the user has moved the highlight to, or `null` while they have not moved
    // it at all. The distinction is the whole of the rule below.
    var moved: Int? by remember { mutableStateOf(null) }
    val matches = remember(connections, query) { ConnectionSearch.match(connections, query) }

    /**
     * Which row Enter would take, before the user has expressed anything.
     *
     * **It is never a production connection.** The saved list is ordered with
     * production first — deliberately, so that in a list you *read* the dangerous
     * servers are never buried — and in a palette where Enter *acts* that same
     * ordering means the default gesture dials production. The two orderings are the
     * same list wanting opposite things from it, and this is where they are told
     * apart: the list stays as it is, and the preselection skips past the red rows.
     *
     * Typing overrides it, because a name typed into a switcher is the user saying
     * which server they mean. Arrowing onto it overrides it for the same reason. What
     * is refused is only the one case where nothing has been said at all.
     */
    val default = remember(matches, query) {
        if (query.isBlank()) {
            matches.indexOfFirst { it.config.environment != Environment.PROD }
        } else {
            matches.indices.firstOrNull() ?: -1
        }
    }

    // Clamped rather than reset on every keystroke: narrowing the list from nine rows
    // to two while the third is highlighted must land somewhere real.
    val index = moved?.coerceIn(0, maxOf(matches.lastIndex, 0)) ?: default
    val chosen = matches.getOrNull(index)

    val field = remember { FocusRequester() }
    val list = rememberLazyListState()

    // The whole point of the chord is that the caret is already in the box.
    LaunchedEffect(Unit) { field.requestFocus() }
    // Arrowing past the bottom of a list of nine on a short window would otherwise
    // move a highlight nobody can see.
    LaunchedEffect(index) { if (index in matches.indices) list.animateScrollToItem(index) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 560.dp)
                .border(Sizes.hairline, Caracal.colors.hairline, MaterialTheme.shapes.extraLarge)
                .testTag("connection-switcher"),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Caracal.colors.paneHeader)
                        .padding(horizontal = Space.lg, vertical = Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(Glyphs.CONNECTIONS, size = 16)
                    InlineField(
                        value = query,
                        onValueChange = {
                            query = it
                            moved = null
                        },
                        tag = "switcher-search",
                        placeholder = "Go to connection…",
                        monospace = false,
                        focus = field,
                        onSubmit = { chosen?.let(onChoose) },
                        onKey = { event ->
                            when (event.key) {
                                // From "nothing is chosen", down lands on the first
                                // row and up on the last — including the production
                                // ones, which is the deliberate act the default was
                                // withholding.
                                Key.DirectionDown -> {
                                    moved = (index + 1).coerceIn(0, matches.lastIndex)
                                    true
                                }

                                Key.DirectionUp -> {
                                    moved = if (index < 0) matches.lastIndex else (index - 1).coerceAtLeast(0)
                                    true
                                }

                                else -> false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Hairline()

                if (matches.isEmpty()) {
                    NoMatch(query = query, any = connections.isNotEmpty())
                } else {
                    LazyColumn(
                        state = list,
                        // Tall enough to be a list and short enough to stay a palette.
                        // Nine rows is more connections than most people save.
                        modifier = Modifier.heightIn(max = 330.dp),
                    ) {
                        itemsIndexed(matches, key = { _, view -> view.id.value }) { position, view ->
                            SwitcherRow(
                                view = view,
                                position = position,
                                highlighted = position == index,
                                onClick = { onChoose(view) },
                            )
                        }
                    }
                }

                Hairline()
                Legend(shortcuts, chosen = chosen != null)
            }
        }
    }
}

/**
 * One connection, carrying everything that decides whether the user meant this one.
 *
 * The highlight is a fill *and* a stripe, for the reason the sidebar's selection is:
 * a fill is a colour difference and a colour difference is what disappears on a
 * projector, in a screenshot, and for a colour-blind reader — and the row this is
 * about is sometimes production.
 */
@Composable
private fun SwitcherRow(
    view: ConnectionView,
    position: Int,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val config = view.config
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .hoverHighlight()
            // The highlight is what Enter would choose, which is what `selected`
            // means here: the switcher is a list with a cursor in it.
            .selectable(selected = highlighted, onClick = onClick)
            .handCursor()
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag("switcher-row-$position"),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(Sizes.selectionStripe)
                .fillMaxHeight()
                .background(if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        EngineLogo(config.engineId, size = 14.dp, described = true)
        ColorSwatch(config.color)
        Text(
            text = config.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        EnvironmentBadge(config.environment)
        if (config.readOnly) ReadOnlyBadge()
        StatusBadge(view.runtime)
    }
}

/**
 * The chords the palette itself answers, said out loud along the bottom.
 *
 * A keyboard control whose keys are not written on it is a keyboard control most
 * people will drive with the pointer.
 */
@Composable
private fun Legend(shortcuts: Shortcuts, chosen: Boolean) {
    Text(
        text = if (chosen) {
            "↑↓ to move · Enter to go there · Esc to close · " +
                "${shortcuts.chord(Shortcut.SWITCH)} reopens this"
        } else {
            // Nothing is preselected, which here means every match is production.
            // Saying so is what stops Enter from looking broken.
            "Type a name, or use ↑↓ · Esc to close"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(Caracal.colors.paneHeader)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag("switcher-legend"),
    )
}

/**
 * Nothing matched, in whichever of the two ways that happened.
 *
 * Telling someone with no saved connections that their search matched nothing would
 * be answering a question they did not ask.
 */
@Composable
private fun NoMatch(query: String, any: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().padding(Space.xxl), contentAlignment = Alignment.Center) {
        if (any) {
            Text(
                text = "No connection is called \"$query\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("switcher-no-match"),
            )
        } else {
            Text(
                text = "No connections yet. Choose New in the sidebar to add one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("switcher-empty"),
            )
        }
    }
}
