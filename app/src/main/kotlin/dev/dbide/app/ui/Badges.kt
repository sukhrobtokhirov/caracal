package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.RuntimeState
import dev.dbide.core.connections.RuntimeStatus

/**
 * The safety signals.
 *
 * Production and read-only are spelled out in words as well as colour: colour alone
 * fails for a colour-blind user, on a projector, and in a screenshot, and this is
 * the signal that stops someone running a query against the wrong server.
 */
@Composable
fun EnvironmentBadge(environment: Environment, modifier: Modifier = Modifier) {
    val colors = environmentColors(environment)
    Badge(
        text = environment.wire.uppercase(),
        background = colors.container,
        foreground = colors.onContainer,
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

@Composable
fun EngineBadge(engine: Engine, modifier: Modifier = Modifier) {
    Badge(
        text = engine.wire.uppercase(),
        background = MaterialTheme.colorScheme.surfaceVariant,
        foreground = MaterialTheme.colorScheme.onSurfaceVariant,
        description = "engine-${engine.wire}",
        modifier = modifier,
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = "status-${label.lowercase()}" },
    ) {
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor(state.status)),
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** The user's chosen colour, as a swatch. Decorative: it never carries meaning alone. */
@Composable
fun ColorSwatch(color: String?, modifier: Modifier = Modifier) {
    val parsed = color?.let(::parseHexColor) ?: return
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(parsed)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
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
        color = foreground,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp)
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
