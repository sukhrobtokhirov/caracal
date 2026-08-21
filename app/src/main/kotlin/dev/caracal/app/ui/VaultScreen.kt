package dev.caracal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.caracal.app.ThemeViewModel
import dev.caracal.app.VaultUiState
import dev.caracal.app.VaultViewModel

/**
 * First run and returning-user states, in one screen.
 *
 * The difference between them is stated plainly, because they carry different
 * consequences: on first run the user is choosing a password that cannot be
 * recovered, and on a later run they are simply typing one they already have.
 *
 * The form sits on a raised card rather than loose on the background. It is the only
 * thing on screen and the only thing to do, and a bounded panel says that in a way a
 * column of centred text does not.
 */
@Composable
fun VaultScreen(viewModel: VaultViewModel, theme: ThemeViewModel) {
    val setup = viewModel.screen == VaultUiState.Setup
    val focus = remember { FocusRequester() }

    LaunchedEffect(viewModel.screen) {
        if (viewModel.screen == VaultUiState.Setup || viewModel.screen == VaultUiState.Locked) {
            focus.requestFocus()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            // The lock screen is the first frame of every launch, so the theme control
            // belongs here too: nobody should have to unlock in the dark to ask for
            // light.
            ThemeToggle(
                mode = theme.mode,
                onCycle = theme::cycle,
                modifier = Modifier.align(Alignment.TopEnd).padding(Space.lg),
            )

            Box(
                modifier = Modifier.fillMaxSize().padding(Space.xxl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Space.xl),
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(Sizes.hairline, Caracal.colors.hairline, MaterialTheme.shapes.large)
                        .padding(Space.xxl),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        Text(
                            if (setup) "Choose a master password" else "Unlock Caracal",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            if (setup) {
                                "It encrypts every database password you save. It is never stored " +
                                    "anywhere, so it cannot be recovered — if you forget it, your saved " +
                                    "passwords are gone."
                            } else {
                                "Enter your master password to use your saved connections."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                        OutlinedTextField(
                            value = viewModel.password,
                            onValueChange = viewModel::onPasswordChange,
                            label = { Text("Master password") },
                            singleLine = true,
                            enabled = !viewModel.busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (setup) ImeAction.Next else ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .testTag("master-password"),
                        )

                        if (setup) {
                            OutlinedTextField(
                                value = viewModel.confirmation,
                                onValueChange = viewModel::onConfirmationChange,
                                label = { Text("Confirm master password") },
                                singleLine = true,
                                enabled = !viewModel.busy,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("confirm-master-password"),
                            )
                            Text(
                                "At least ${viewModel.minimumPasswordLength} characters. Length matters " +
                                    "more here than punctuation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    viewModel.setupHint?.let { hint ->
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("vault-hint"),
                        )
                    }

                    // The message is the same for every wrong password, so a failed attempt
                    // tells an onlooker nothing beyond "not this one".
                    viewModel.failure?.let { failure -> ErrorBanner(failure) }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            shape = MaterialTheme.shapes.small,
                            onClick = viewModel::submit,
                            enabled = viewModel.canSubmit,
                            modifier = Modifier.testTag("vault-submit"),
                        ) {
                            Text(if (setup) "Create master password" else "Unlock")
                        }

                        // Argon2id is deliberately slow, and a second of nothing happening
                        // is indistinguishable from a button that did not register the
                        // click. The label says which of the two it is.
                        if (viewModel.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "Deriving the key…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("vault-busy"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shown when the configuration database itself cannot be opened. */
@Composable
fun VaultUnavailableScreen(screen: VaultUiState.Unavailable) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().padding(Space.xxl),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Space.lg),
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(Sizes.hairline, Caracal.colors.hairline, MaterialTheme.shapes.large)
                    .padding(Space.xxl),
            ) {
                Text("Caracal cannot start", style = MaterialTheme.typography.headlineSmall)
                ErrorBanner(screen.failure)
            }
        }
    }
}
