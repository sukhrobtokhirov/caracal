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
import dev.dbide.core.result.DbError
import dev.dbide.core.result.Failure

/**
 * A failure, as the user sees it.
 *
 * The message comes from `:core` already classified and already safe: nothing here
 * formats a driver string or a stack trace. The code is shown in small type because
 * it is what a bug report should quote.
 *
 * A server error brings more than a message, and §2.9 is about not throwing the
 * rest away. The hint in particular is worth the space: PostgreSQL only emits one
 * when it knows what you probably meant, and "Perhaps you meant to reference the
 * column \"o.total\"" is frequently the whole answer, sitting one field away from a
 * message that says only that a column does not exist.
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
        failure.query?.let { QueryErrorDetail(it) }
        Text(
            // The SQLSTATE, when there is one, is more useful to quote than the
            // application's own code: it is the identifier PostgreSQL's documentation
            // is indexed by, and it survives a search engine.
            text = listOfNotNull(
                failure.code,
                failure.query?.sqlState,
                // Only when it is not the ordinary one. A red banner already says
                // "error"; FATAL and PANIC say the session or the server is gone, and
                // that is worth a word.
                failure.query?.severity?.takeUnless { it.equals("ERROR", ignoreCase = true) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
        )
    }
}

/**
 * Everything the server said beyond its first sentence.
 *
 * Each part is labelled with PostgreSQL's own word for it — `DETAIL`, `HINT` — so
 * that what is on screen is recognizably the same report `psql` would have printed,
 * and someone who has read one can read this.
 */
@Composable
private fun QueryErrorDetail(query: DbError.QueryFailed) {
    val foreground = MaterialTheme.colorScheme.onErrorContainer
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        query.detail?.let { ErrorField("DETAIL", it, foreground) }
        query.hint?.let { ErrorField("HINT", it, foreground) }
        query.subject?.describe()?.let { ErrorField("AT", it, foreground) }
        // Reported, never used to point at anything: it counts into a query the
        // server generated inside a function body, which is not on screen and may
        // never have been.
        query.internalPosition?.let {
            ErrorField("INTERNAL POSITION", it.toString(), foreground)
        }
    }
}

@Composable
private fun ErrorField(label: String, value: String, foreground: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = foreground.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = foreground.copy(alpha = 0.9f),
            modifier = Modifier.semantics { contentDescription = "error-${label.lowercase()}" },
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
