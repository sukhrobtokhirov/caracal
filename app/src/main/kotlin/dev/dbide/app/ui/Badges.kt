package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.RuntimeState
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.redis.KeyType

/**
 * The safety signals.
 *
 * Production and read-only are spelled out in words as well as colour: colour alone
 * fails for a colour-blind user, on a projector, and in a screenshot, and this is
 * the signal that stops someone running a query against the wrong server.
 */
/**
 * [inverted] is for the one place the badge sits on its own colour: the shell turns
 * `errorContainer` when production is selected, and an `errorContainer` chip on an
 * `errorContainer` bar is a chip nobody can see. Swapping the two puts the badge
 * back on top of the warning it belongs to.
 */
@Composable
fun EnvironmentBadge(
    environment: Environment,
    modifier: Modifier = Modifier,
    inverted: Boolean = false,
) {
    val colors = environmentColors(environment)
    Badge(
        text = environment.wire.uppercase(),
        background = if (inverted) colors.onContainer else colors.container,
        foreground = if (inverted) colors.container else colors.onContainer,
        description = "environment-${environment.wire}",
        modifier = modifier,
    )
}

@Composable
fun ReadOnlyBadge(modifier: Modifier = Modifier) {
    Badge(
        text = "READ ONLY",
        background = MaterialTheme.colorScheme.secondaryContainer,
        foreground = MaterialTheme.colorScheme.onSecondaryContainer,
        description = "read-only",
        modifier = modifier,
    )
}

/**
 * Which engine, outlined rather than filled.
 *
 * A row can carry three of these at once, and three filled pills side by side is a
 * row that reads as decoration. The engine is the least urgent of the three, so it
 * is the one that gives up its fill.
 */
@Composable
fun EngineBadge(engine: Engine, modifier: Modifier = Modifier) {
    Text(
        text = engine.wire.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .border(Sizes.hairline, Dbide.colors.hairline, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = Space.sm, vertical = 1.dp)
            .semantics { contentDescription = "engine-${engine.wire}" },
    )
}

/** The live client state: a dot, and the word for it. */
@Composable
fun StatusBadge(state: RuntimeState, modifier: Modifier = Modifier) {
    val label = when (state.status) {
        RuntimeStatus.CLOSED -> "Closed"
        RuntimeStatus.OPENING -> "Opening"
        RuntimeStatus.OPEN -> "Open"
        RuntimeStatus.ERROR -> "Error"
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = "status-${label.lowercase()}" },
    ) {
        Box(
            modifier = Modifier.size(7.dp).clip(CircleShape).background(statusColor(state.status)),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Which Redis type a key holds.
 *
 * Outlined and monospace, like the engine badge and for the same reason: a key row
 * already carries a countdown and a size, and a third filled pill on it would make
 * the row read as decoration rather than as data. The word itself is Redis's own —
 * `zset`, not "sorted set" — because that is what `TYPE` answers and what the user
 * would type into a filter.
 */
@Composable
fun KeyTypeBadge(type: KeyType, modifier: Modifier = Modifier) {
    Text(
        text = type.wire,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .border(Sizes.hairline, Dbide.colors.hairline, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = Space.sm, vertical = 1.dp)
            .semantics { contentDescription = "key-type-${type.wire}" },
    )
}

/** The user's chosen colour, as a swatch. Decorative: it never carries meaning alone. */
@Composable
fun ColorSwatch(color: String?, modifier: Modifier = Modifier) {
    val parsed = color?.let(::parseHexColor) ?: return
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(parsed)
            .border(Sizes.hairline, Dbide.colors.hairline, CircleShape),
    )
}

@Composable
private fun Badge(
    text: String,
    background: Color,
    foreground: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = foreground,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(background)
            .padding(horizontal = Space.sm, vertical = 1.dp)
            .semantics { contentDescription = description },
    )
}

/** Production is red wherever it appears, in both light and dark themes. */
private class EnvironmentColors(val container: Color, val onContainer: Color)

@Composable
private fun environmentColors(environment: Environment) = when (environment) {
    Environment.PROD -> EnvironmentColors(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )

    Environment.STAGING -> EnvironmentColors(
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
    )

    Environment.DEV -> EnvironmentColors(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun statusColor(status: RuntimeStatus) = when (status) {
    RuntimeStatus.OPEN -> MaterialTheme.colorScheme.primary
    RuntimeStatus.OPENING -> MaterialTheme.colorScheme.tertiary
    RuntimeStatus.ERROR -> MaterialTheme.colorScheme.error
    RuntimeStatus.CLOSED -> MaterialTheme.colorScheme.outline
}

/** Validation has already rejected anything but `#rrggbb`, so this cannot fail. */
internal fun parseHexColor(value: String): Color? {
    val hex = value.removePrefix("#")
    if (hex.length != 6) return null
    val rgb = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}
