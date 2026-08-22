package dev.caracal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.caracal.app.PendingCommand
import dev.caracal.core.connections.Environment
import dev.caracal.core.policy.Acknowledgement

/**
 * The question a guarded command raises.
 *
 * Same shape as M2's write confirmation and the same reasoning behind the two
 * strengths: on development a click is enough, and on production the phrase has to be
 * typed because the failure being prevented is not misunderstanding but reflex.
 *
 * What is different is the warning. A SQL confirmation can say "this statement
 * modifies data" and be done, because the user wrote the statement and knows what it
 * does. A Redis one cannot: `SWAPDB` is four keystrokes that exchange two entire
 * databases, and `DEBUG SLEEP` is a single-threaded server held for as long as it says.
 * So the guard's own sentence about what the command does is shown above everything
 * else, and it is the reason this dialog is worth reading rather than dismissing.
 *
 * The consent it collects is used once. There is no toggle, no "don't ask again", and
 * nowhere for either to be stored — [dev.caracal.engine.api.CommandConsent] is an
 * argument to one call, so §3.10's single-use rule is not enforced here so much as it
 * is unrepresentable.
 */
@Composable
fun CommandConfirmation(
    pending: PendingCommand,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val clearance = pending.clearance
    val production = clearance.environment == Environment.PROD
    var typed by remember(pending) { mutableStateOf("") }
    val satisfied = clearance.satisfiedBy(typed)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Run ${clearance.command}?") },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
            ) {
                if (production) ProductionRow(clearance.connectionName)

                Text(
                    // The guard's own words about what this command does. "Are you
                    // sure?" above a command the user just typed adds nothing;
                    // knowing that SWAPDB exchanges two whole databases might.
                    text = clearance.warning,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("command-warning"),
                )

                CommandPreview(clearance.command)

                Text(
                    text = "This applies to this one command. Nothing is remembered, " +
                        "and the next one is asked again.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("command-single-use"),
                )

                if (clearance.acknowledgement == Acknowledgement.TYPED) {
                    clearance.phrase?.let { phrase ->
                        TypedPhrase(
                            phrase = phrase,
                            typed = typed,
                            onTyped = { typed = it },
                            onSubmit = { if (satisfied) onConfirm(typed) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(typed) },
                enabled = satisfied,
                modifier = Modifier.testTag("confirm-command"),
            ) {
                Text(
                    text = "Run it",
                    color = if (satisfied) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("cancel-command"),
            ) {
                Text("Cancel")
            }
        },
        modifier = Modifier.testTag("command-confirmation"),
    )
}

/** The standing production warning, repeated where the modal cannot dim it. */
@Composable
private fun ProductionRow(connectionName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(Space.lg)
            .testTag("command-prod-banner"),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EnvironmentBadge(Environment.PROD)
        Text(
            text = connectionName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * The command, by name.
 *
 * The name and its subcommand, which is what the guard judged — never the arguments.
 * A confirmation that quoted the whole line would put `CONFIG SET requirepass …` on
 * screen, and the dialog exists to prevent an accident, not to display a password.
 */
@Composable
private fun CommandPreview(command: String) {
    Text(
        text = command,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(Sizes.hairline, Caracal.colors.hairline, MaterialTheme.shapes.medium)
            .padding(Space.lg)
            .testTag("command-name"),
    )
}

/** The production gate: the connection's name and the command's, typed out. */
@Composable
private fun TypedPhrase(
    phrase: String,
    typed: String,
    onTyped: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(phrase) { focus.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(
            text = "Type $phrase to confirm.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = onTyped,
            singleLine = true,
            placeholder = { Text(phrase, fontFamily = FontFamily.Monospace) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag("command-acknowledgement"),
        )
    }
}
