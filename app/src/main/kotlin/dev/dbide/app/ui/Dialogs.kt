package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The application's modal: a panel that owns the window until it is answered.
 *
 * Everything that *changes* something now arrives here rather than in a pane — a new
 * connection, an edit, the appearance settings. The reason is the pane it replaced.
 * A form rendered into the working area had to share the window with a sidebar, an
 * object browser, and a result grid describing a different server, and there was no
 * moment where the application could say "this is what you are doing now". A modal
 * says exactly that, and it says it in the shape a desktop user already knows: dim
 * what is behind, put the title at the top and the two buttons at the bottom, close
 * on Escape.
 *
 * The layout is the one a settings window has, because that is the shape this
 * content is: a rail down the left naming the sections, one section on the right at
 * a time, and a footer that commits or abandons the lot. [rail] is optional — a
 * dialog with one section is a dialog with no rail, and it keeps the same chrome.
 *
 * It is sized as a fraction of the window with a ceiling rather than at a fixed
 * size, so it is a comfortable panel on a 27-inch display and still fits inside the
 * 760×480 the window is allowed to shrink to.
 */
@Composable
fun AppDialog(
    title: String,
    tag: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    maxWidth: Dp = 880.dp,
    maxHeight: Dp = 720.dp,
    /**
     * Whether a click on the scrim closes it.
     *
     * True for a window that only shows things. False for one holding typed input:
     * a mis-aimed click outside a half-filled connection form would throw the form
     * away, and Escape and Cancel are both still there for someone who meant it.
     */
    dismissOnClickOutside: Boolean = true,
    /**
     * Whether the body scrolls as one column.
     *
     * True for a form, which is a stack of fields as tall as it needs to be. False
     * for a window whose content does its own scrolling — a list long enough to
     * deserve a lazy one cannot be measured inside a parent of unbounded height, and
     * a history of a thousand statements is exactly that list. Turning it off also
     * turns off the body padding, because a list that stops short of the window's
     * edge cannot use the whole of it for rows.
     */
    scrolling: Boolean = true,
    rail: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // Escape closes it, and so does the scrim. Both are the same promise: a
            // dialog that has to be dismissed with the pointer is a dialog that has
            // taken the window hostage.
            dismissOnBackPress = true,
            dismissOnClickOutside = dismissOnClickOutside,
            // Material's dialog is sized for a phone alert. This one is a window.
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = maxWidth)
                .fillMaxHeight(0.9f)
                .heightIn(max = maxHeight)
                .border(Sizes.hairline, Dbide.colors.hairline, MaterialTheme.shapes.extraLarge)
                .testTag(tag),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                DialogHeader(title = title, subtitle = subtitle, icon = icon, onDismiss = onDismiss)
                Hairline()

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (rail != null) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(Space.xs),
                            modifier = Modifier
                                .width(Sizes.dialogRail)
                                .fillMaxHeight()
                                .background(Dbide.colors.chrome)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = Space.lg, horizontal = Space.md),
                            content = rail,
                        )
                        VerticalHairline()
                    }
                    Column(
                        verticalArrangement = if (scrolling) {
                            Arrangement.spacedBy(Space.xl)
                        } else {
                            Arrangement.Top
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (scrolling) {
                                    Modifier.verticalScroll(rememberScrollState()).padding(Space.xxl)
                                } else {
                                    Modifier
                                },
                            ),
                        content = content,
                    )
                }

                if (footer != null) {
                    Hairline()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Dbide.colors.paneHeader)
                            .padding(horizontal = Space.xxl, vertical = Space.lg),
                        horizontalArrangement = Arrangement.spacedBy(Space.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        content = footer,
                    )
                }
            }
        }
    }
}

/**
 * The title strip: what this dialog is, and the one control that is always here.
 *
 * The close cross is drawn even when the dialog has a Cancel button under it. They
 * are not the same gesture — Cancel is an answer to the question, and the cross is
 * "I opened the wrong thing" — and a window without one is a window a user has to
 * guess Escape at.
 */
@Composable
private fun DialogHeader(
    title: String,
    subtitle: String?,
    icon: (@Composable () -> Unit)?,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Dbide.colors.paneHeader)
            .padding(start = Space.xxl, end = Space.lg, top = Space.lg, bottom = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.xs),
            modifier = Modifier.weight(1f),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ToolButton(text = "✕", onClick = onDismiss, tag = "close-dialog")
    }
}

/**
 * One entry in a dialog's rail.
 *
 * Selected entries get a filled background *and* a stripe down the leading edge, for
 * the same reason the connection list's selection does: a fill is a colour
 * difference, and a colour difference is the thing that disappears on a projector.
 */
@Composable
fun RailItem(
    label: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                MaterialTheme.shapes.medium,
            )
            .hoverHighlight(MaterialTheme.shapes.medium, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .handCursor(enabled)
            .padding(horizontal = Space.md, vertical = Space.md)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(Sizes.selectionStripe)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        leading?.invoke()
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A rail's own heading — "Engine", "Application". Not clickable, and not a control. */
@Composable
fun RailHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Space.md, top = Space.md, bottom = Space.xs),
    )
}

/**
 * A titled group of fields inside a dialog's body.
 *
 * The heading carries a glyph and the fields sit under it with the section's own
 * spacing, which is what turns a scroll of eleven text fields into four things to
 * decide about.
 */
@Composable
fun DialogSection(
    title: String,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    detail: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            glyph?.let { Glyph(it, size = 12) }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}
