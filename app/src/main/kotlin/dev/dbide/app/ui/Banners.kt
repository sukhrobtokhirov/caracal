package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.dbide.core.connections.TestResult
import dev.dbide.core.result.Failure

/**
 * A failure, as the user sees it.
 *
 * The message comes from `:core` already classified and already safe: nothing here
 * formats a driver string or a stack trace. The code is shown in small type because
 * it is what a bug report should quote.
 */
@Composable
fun ErrorBanner(failure: Failure, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Banner(
        background = MaterialTheme.colorScheme.errorContainer,
        foreground = MaterialTheme.colorScheme.onErrorContainer,
        description = "failure-${failure.code}",
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Text(failure.message, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            failure.code,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
        )
    }
}

/** A successful connection test: what was reached, and how quickly. */
@Composable
fun TestResultBanner(result: TestResult, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Banner(
        background = MaterialTheme.colorScheme.primaryContainer,
        foreground = MaterialTheme.colorScheme.onPrimaryContainer,
        description = "test-succeeded",
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Text(
            "Connected in ${result.latencyMillis} ms.",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        result.serverVersion?.let { version ->
            Text(
                "${result.engine.wire} $version",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun Banner(
    background: Color,
    foreground: Color,
    description: String,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(12.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) { Text("Dismiss", color = foreground) }
        }
    }
}
