package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * The pieces every pane is built from.
 *
 * They are here rather than repeated per file for the reason any of this is: a
 * divider, a pane header, and a hovered row should be the same divider, header, and
 * hover in the tree, the grid, and the sidebar, and the only way that stays true
 * through a change is if there is one of each.
 */

/** The application's divider: a true hairline, dimmer than Material's rule. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.hairline)
            .background(Dbide.colors.hairline),
    )
}

/** The same rule, standing up between two panes. */
@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(Sizes.hairline)
            .background(Dbide.colors.hairline),
    )
}

/**
 * A pane's title strip.
 *
 * Fixed height and a shade of its own, so that three panes side by side line up
 * across the top and read as three panes rather than three documents.
 */
@Composable
fun PaneHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.paneHeader)
            .background(Dbide.colors.paneHeader)
            .padding(start = Space.lg, end = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * A dense action in a header or a status strip.
 *
 * Material's `TextButton` carries a 12dp vertical inset and a 40dp minimum height,
 * which is a touch target. In a 32dp header strip there is no room for it, and
 * three of them in a row is a header twice the height of the pane title it sits
 * beside.
 */
@Composable
fun ToolButton(
    text: String,
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: ToolEmphasis = ToolEmphasis.NORMAL,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        emphasis == ToolEmphasis.PRIMARY -> MaterialTheme.colorScheme.primary
        emphasis == ToolEmphasis.DANGER -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .hoverHighlight(MaterialTheme.shapes.small, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .handCursor(enabled)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .semantics { contentDescription = description },
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

enum class ToolEmphasis { NORMAL, PRIMARY, DANGER }

/**
 * The theme control, in the shell and on the lock screen.
 *
 * On the lock screen because that is the first thing drawn: someone who wants light
 * should not have to unlock in the dark to ask for it.
 */
@Composable
fun ThemeToggle(mode: ThemeMode, onCycle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .border(Sizes.hairline, Dbide.colors.hairline, MaterialTheme.shapes.small)
            .hoverHighlight(MaterialTheme.shapes.small)
            .clickable(onClick = onCycle)
            .handCursor()
            .padding(horizontal = Space.md, vertical = Space.sm)
            .semantics { contentDescription = "theme-toggle" },
    ) {
        Text(
            // The word rather than a glyph: a sun and a moon are two more characters
            // that can arrive as an empty box on a machine with a minimal font set.
            text = mode.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A pane with nothing in it yet, saying what would put something there.
 *
 * §4.7's rule, applied early: a blank pane is indistinguishable from a broken one,
 * and the difference between "no rows matched" and "nothing has been run" is the
 * difference between a finished action and an unstarted one.
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.md),
            modifier = Modifier.widthIn(max = 340.dp).padding(Space.xxl),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = description },
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            action?.invoke()
        }
    }
}

/**
 * The background a row takes under the pointer.
 *
 * Hover is the cheapest affordance a desktop application has and the one a web-era
 * layout most often forgets: it is how a list of forty names tells you which one a
 * click would land on before you spend the click.
 */
@Composable
fun Modifier.hoverHighlight(shape: Shape = RectangleShape, enabled: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return this
        .hoverable(source, enabled = enabled)
        .background(if (hovered && enabled) Dbide.colors.hover else Color.Transparent, shape)
}

/** The pointer a clickable thing deserves. Desktop users read the cursor. */
fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR))) else this

/**
 * A single-line input sized for a header strip.
 *
 * Material's `OutlinedTextField` is 56dp tall before its label, which is the right
 * size for a form and twice the height of the toolbar this has to sit in. The form
 * keeps Material's; the search box and the command line get this.
 *
 * [onSubmit] is Enter, and it is handled as a preview so the key never reaches the
 * field as a character. [onKey] is for the console's history recall, which needs the
 * arrows before the field decides they move the caret.
 */
@Composable
fun InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    monospace: Boolean = true,
    onSubmit: (() -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
) {
    val style = MaterialTheme.typography.bodySmall.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
    )

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .border(Sizes.hairline, Dbide.colors.hairline, MaterialTheme.shapes.small)
            .padding(horizontal = Space.md, vertical = Space.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                text = placeholder,
                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (onKey?.invoke(event) == true) return@onPreviewKeyEvent true
                    if (event.key != Key.Enter || onSubmit == null) return@onPreviewKeyEvent false
                    onSubmit()
                    true
                }
                .semantics { contentDescription = description },
        )
    }
}

/**
 * A [ToolButton] that opens a menu underneath itself.
 *
 * The same menu the right-click gesture opens, because a filter with seven values is
 * seven chips wide and the pane it belongs in is 320 device-independent pixels.
 */
@Composable
fun MenuButton(
    text: String,
    description: String,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ToolButton(
            text = "$text ▾",
            onClick = { open = true },
            description = description,
            enabled = enabled,
        )
        ContextMenu(
            expanded = open,
            at = DpOffset.Zero,
            actions = actions,
            onDismiss = { open = false },
            description = "$description-menu",
        )
    }
}
