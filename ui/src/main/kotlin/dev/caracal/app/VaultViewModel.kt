package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.caracal.core.connections.ConnectionService
import dev.caracal.core.connections.Secret
import dev.caracal.core.result.Failure
import dev.caracal.core.result.toFailure
import dev.caracal.core.vault.Vault
import dev.caracal.core.vault.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the vault screen is showing. The UI renders cases; it never parses messages. */
sealed interface VaultUiState {
    /** The stored state has not been read yet. */
    data object Loading : VaultUiState

    /** First run: the user chooses a master password. */
    data object Setup : VaultUiState

    /** A master password exists and the application is waiting for it. */
    data object Locked : VaultUiState

    /** The key is held; the workspace is usable. */
    data object Unlocked : VaultUiState

    /** The configuration database could not be opened at all. */
    data class Unavailable(val failure: Failure) : VaultUiState
}

/**
 * Owns the setup and unlock flow.
 *
 * The password fields are held as `String` because that is what a Compose text
 * field is. They are converted to a [Secret] and cleared at the moment of use —
 * the JVM cannot un-intern the `String`, which is a limitation of typing a
 * password into any Swing or Compose field, not of the vault.
 */
class VaultViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
    private val onUnlocked: () -> Unit = {},
) {
    var screen: VaultUiState by mutableStateOf(VaultUiState.Loading)
        private set

    var password: String by mutableStateOf("")
        private set

    var confirmation: String by mutableStateOf("")
        private set

    var busy: Boolean by mutableStateOf(false)
        private set

    var failure: Failure? by mutableStateOf(null)
        private set

    val minimumPasswordLength = Vault.MIN_PASSWORD_LENGTH

    /** Whether the current input could be submitted at all. */
    val canSubmit: Boolean
        get() = !busy && password.isNotEmpty() &&
            (screen != VaultUiState.Setup || (password.length >= minimumPasswordLength && password == confirmation))

    /** The reason the setup form is not submittable yet, if the user has typed something. */
    val setupHint: String?
        get() = when {
            screen != VaultUiState.Setup || password.isEmpty() -> null
            password.length < minimumPasswordLength ->
                "Use at least $minimumPasswordLength characters."
            confirmation.isNotEmpty() && password != confirmation -> "The two passwords do not match."
            else -> null
        }

    fun onPasswordChange(value: String) {
        password = value
        failure = null
    }

    fun onConfirmationChange(value: String) {
        confirmation = value
        failure = null
    }

    /** Reads the stored state to decide between first run and a returning user. */
    fun load() {
        scope.launch {
            screen = try {
                when (service.vaultState()) {
                    VaultState.SETUP_REQUIRED -> VaultUiState.Setup
                    VaultState.LOCKED -> VaultUiState.Locked
                    VaultState.UNLOCKED -> VaultUiState.Unlocked
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                VaultUiState.Unavailable(problem.toFailure())
            }
        }
    }

    /** Chooses the master password on first run, or unlocks with an existing one. */
    fun submit() {
        if (!canSubmit) return
        val setup = screen == VaultUiState.Setup
        busy = true
        failure = null
        scope.launch {
            val secret = Secret(password)
            try {
                if (setup) service.setUp(secret) else service.unlock(secret)
                clearInput()
                screen = VaultUiState.Unlocked
                onUnlocked()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                failure = problem.toFailure()
            } finally {
                secret.clear()
                busy = false
            }
        }
    }

    /** Discards the key, closes every client, and returns to the unlock screen. */
    fun lock() {
        scope.launch {
            // Guarded like every other launch here. This was the one that was not,
            // and anything thrown out of `service.lock()` used to leave `screen` at
            // Unlocked over a workspace that had already been torn down. The screen
            // moves to Locked either way: the key is gone whether or not closing the
            // clients raised on the way.
            try {
                service.lock()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                failure = problem.toFailure()
            } finally {
                clearInput()
                screen = VaultUiState.Locked
            }
        }
    }

    private fun clearInput() {
        password = ""
        confirmation = ""
    }
}
