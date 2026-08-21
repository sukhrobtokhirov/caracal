package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import dev.dbide.app.Shortcut
import dev.dbide.app.ShortcutGroup
import dev.dbide.app.Shortcuts

/**
 * §4.4's searchable shortcut reference.
 *
 * It reads [Shortcut] rather than listing anything of its own, which is the point of
 * that enumeration existing: a help window is the one screen in an application that
 * can be wrong for a year without anybody noticing, and the only defence is for it to
 * be incapable of disagreeing with the handler.
 *
 * The chords are drawn for the machine this is running on. A window that told a Mac
 * user to press `Ctrl+T` would be worse than no window, because they would try it.
 */
@Composable
fun ShortcutsDialog(shortcuts: Shortcuts, onDismiss: () -> Unit) {
    var query: String by remember { mutableStateOf("") }
    val matches = remember(query, shortcuts) { Shortcut.search(query, shortcuts.platform) }

    AppDialog(
        title = "Keyboard shortcuts",
        subtitle = "Every one of these is also a button somewhere.",
        description = "shortcuts-window",
        icon = { Glyph(Glyphs.SHORTCUTS, size = 18) },
        maxWidth = 620.dp,
        maxHeight = 560.dp,
        onDismiss = onDismiss,
        footer = {
            Box(modifier = Modifier.weight(1f))
            ToolButton(text = "Close", onClick = onDismiss, description = "shortcuts-close")
        },
    ) {
        InlineField(
            value = query,
            onValueChange = { query = it },
            description = "shortcuts-search",
            placeholder = "Search shortcuts",
            monospace = false,
            modifier = Modifier.fillMaxWidth(),
        )

        if (matches.isEmpty()) {
            Text(
                text = "Nothing here matches \"$query\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "shortcuts-no-match" },
            )
            return@AppDialog
        }

        // Grouped in the enumeration's own order rather than alphabetically: the
        // groups are a rough order of use, and a reference sorted by first letter is
        // a reference you have to read all of.
        ShortcutGroup.entries.forEach { group ->
            val rows = matches.filter { it.group == group }
            if (rows.isEmpty()) return@forEach
            DialogSection(title = group.title) {
                rows.forEach { ShortcutRow(it, shortcuts) }
            }
        }
    }
}

/** One chord, what it does, and the sentence that says when it does it. */
@Composable
private fun ShortcutRow(shortcut: Shortcut, shortcuts: Shortcuts) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "shortcut-${shortcut.name.lowercase()}" },
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
        verticalAlignment = Alignment.Top,
    ) {
        // The fixed width is on the column rather than on the key itself: the chords
        // line up into something the eye can run down, which is the only reason a
        // reference like this is faster than reading the same facts as prose — while
        // the key stays the size of the key. A 92dp box around `⌘T` reads as an empty
        // text field.
        Box(modifier = Modifier.width(92.dp)) {
            Text(
                text = shortcuts.chord(shortcut),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Dbide.colors.chrome)
                    .border(Sizes.hairline, Dbide.colors.hairline, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = Space.sm, vertical = Space.xs),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.weight(1f)) {
            Text(
                text = shortcut.action,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = shortcut.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
