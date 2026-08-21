package dev.caracal.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The right-click menu, and the gesture that opens it.
 *
 * Compose Desktop ships a `ContextMenuArea`, and it is not used here: it draws
 * itself from the platform's own representation rather than this theme, which on a
 * dark window means a bright rectangle in the middle of a dark one. A menu is
 * chrome, and chrome that does not match the application is chrome the eye stops on.
 */

/**
 * One line of a context menu.
 *
 * [tag] is what the accessibility tree and the tests call it — the label is
 * for the person reading it, and the two are allowed to drift.
 */
data class MenuAction(
    val label: String,
    val tag: String,
    val enabled: Boolean = true,
    /** Drawn in the error colour, and set apart from what is above it. */
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Calls [onOpen] with where the pointer was when the secondary button went down.
 *
 * The position is in the modified element's own coordinates, which is exactly what
 * [ContextMenu] wants: a menu opens under the pointer, not under the row.
 */
fun Modifier.onSecondaryClick(onOpen: (Offset) -> Unit): Modifier = pointerInput(onOpen) {
    awaitPointerEventScope {
        while (true) {
            // The main pass, so the row's own click handling still sees a primary
            // press. Only the secondary press is taken, and only it is consumed.
            val event = awaitPointerEvent(PointerEventPass.Main)
            if (event.type != PointerEventType.Press) continue
            if (!event.buttons.isSecondaryPressed) continue
            val position = event.changes.firstOrNull()?.position ?: Offset.Zero
            event.changes.forEach { it.consume() }
            onOpen(position)
        }
    }
}

/**
 * A menu of [actions], opened at [at] relative to whatever it is anchored inside.
 *
 * Denser than Material's own menu, which sizes its items for a thumb: the actions
 * on a connection are the same actions as the detail pane's row of buttons, and a
 * 48dp row each turns five of them into half the window.
 */
@Composable
fun ContextMenu(
    expanded: Boolean,
    at: DpOffset,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    tag: String,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = at,
        modifier = Modifier
            .widthIn(min = 176.dp)
            .testTag(tag),
    ) {
        actions.forEach { action ->
            if (action.danger) Hairline(modifier = Modifier.padding(vertical = Space.xs))
            MenuRow(action = action, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun MenuRow(action: MenuAction, onDismiss: () -> Unit) {
    val color = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        action.danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.paneHeader)
            .hoverHighlight(enabled = action.enabled)
            .clickable(enabled = action.enabled) {
                // Dismissed first: the action may replace the pane the menu is
                // anchored in, and a menu outliving its row is a menu left hanging.
                onDismiss()
                action.onClick()
            }
            .handCursor(action.enabled)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag(action.tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = action.label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
