package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
fun ErrorBanner(
    failure: Failure,
    onDismiss: (() -> Unit)? = null,
    note: String? = null,
    modifier: Modifier = Modifier,
) {
    Banner(
        background = MaterialTheme.colorScheme.errorContainer,
        foreground = MaterialTheme.colorScheme.onErrorContainer,
        tag = "failure-${failure.code}",
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Text(
            failure.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        failure.query?.let { QueryErrorDetail(it) }
        // Something the application has to say about the error rather than something
        // the server said, so it is set apart from the report above it in italic. The
        // only one so far is §4.6's: the script moved, so the position is not drawn.
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                modifier = Modifier.testTag("error-note"),
            )
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalAlignment = Alignment.Top) {
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
            modifier = Modifier.testTag("error-${label.lowercase()}"),
        )
    }
}

/** A successful connection test: what was reached, and how quickly. */
@Composable
fun TestResultBanner(result: TestResult, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Banner(
        // Green rather than the accent blue. Blue is what this application uses for
        // "selected" and "focused" — states, not outcomes — and a test that passed is
        // an outcome.
        background = Dbide.colors.successContainer,
        foreground = Dbide.colors.onSuccessContainer,
        tag = "test-succeeded",
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Text(
            "Connected in ${result.latencyMillis} ms.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dbide.colors.onSuccessContainer,
        )
        result.serverVersion?.let { version ->
            Text(
                "${result.engine.wire} $version",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Dbide.colors.onSuccessContainer.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * The shape every banner takes: a tinted panel with a bar of its own colour down the
 * leading edge.
 *
 * The bar is what makes an error and a success distinguishable at the edge of
 * vision. Two tinted rectangles differ only in hue, and hue is the one channel that
 * does not survive a colour-blind reader or a projector — a solid 3dp rule reads as
 * emphasis regardless of what colour it happens to be.
 */
@Composable
private fun Banner(
    background: Color,
    foreground: Color,
    tag: String,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .height(IntrinsicSize.Min)
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(foreground.copy(alpha = 0.65f)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            modifier = Modifier.weight(1f).padding(Space.lg),
        ) {
            content()
        }
        onDismiss?.let {
            Box(modifier = Modifier.padding(Space.sm)) {
                ToolButton(text = "Dismiss", onClick = it, tag = "banner-dismiss")
            }
        }
    }
}
