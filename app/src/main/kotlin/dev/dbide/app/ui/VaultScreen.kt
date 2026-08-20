package dev.dbide.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.dbide.app.VaultUiState
import dev.dbide.app.VaultViewModel

/**
 * First run and returning-user states, in one screen.
 *
 * The difference between them is stated plainly, because they carry different
 * consequences: on first run the user is choosing a password that cannot be
 * recovered, and on a later run they are simply typing one they already have.
 */
@Composable
fun VaultScreen(viewModel: VaultViewModel) {
    val setup = viewModel.screen == VaultUiState.Setup
    val focus = remember { FocusRequester() }

    LaunchedEffect(viewModel.screen) {
        if (viewModel.screen == VaultUiState.Setup || viewModel.screen == VaultUiState.Locked) {
            focus.requestFocus()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.widthIn(max = 420.dp),
            ) {
                Text(
                    if (setup) "Choose a master password" else "Unlock Database IDE",
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
                )

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
                        .semantics { contentDescription = "master-password" },
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
                            .semantics { contentDescription = "confirm-master-password" },
                    )
                    Text(
                        "At least ${viewModel.minimumPasswordLength} characters. Length matters " +
                            "more here than punctuation.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                viewModel.setupHint?.let { hint ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { contentDescription = "vault-hint" },
                    )
                }

                // The message is the same for every wrong password, so a failed attempt
                // tells an onlooker nothing beyond "not this one".
                viewModel.failure?.let { failure -> ErrorBanner(failure) }

                Button(
                    onClick = viewModel::submit,
                    enabled = viewModel.canSubmit,
                    modifier = Modifier.semantics { contentDescription = "vault-submit" },
                ) {
                    Text(if (setup) "Create master password" else "Unlock")
                }

                if (viewModel.busy) {
                    Text(
                        "Deriving the key…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { contentDescription = "vault-busy" },
                    )
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Shown when the configuration database itself cannot be opened. */
@Composable
fun VaultUnavailableScreen(screen: VaultUiState.Unavailable) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.widthIn(max = 480.dp),
            ) {
                Text("Database IDE cannot start", style = MaterialTheme.typography.headlineSmall)
                ErrorBanner(screen.failure)
            }
        }
    }
}
