package dev.caracal.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.caracal.core.result.Failure
import dev.caracal.core.result.toFailure
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** The lifecycle of the one long-lived resource the window owns. */
sealed interface Startup<out T> {
    /** Being opened. The window has nothing to show yet. */
    data object Opening : Startup<Nothing>

    data class Ready<T>(val value: T) : Startup<T>

    /** It could not be opened at all — a missing directory, an unreadable file. */
    data class Failed(val failure: Failure) : Startup<Nothing>
}

/**
 * Opens a resource once and closes it when the composition goes away.
 *
 * The `DisposableEffect` is keyed on `Unit`, and that is the whole point of this
 * function existing. Keying it on the resource — the obvious thing to write — makes
 * Compose dispose the previous effect the instant the resource appears, and the
 * `onDispose` then closes the resource it was meant to be guarding. The application
 * shipped that bug once; the regression test for it is in `StartupTest`.
 */
@Composable
fun <T : AutoCloseable> rememberStartup(open: suspend () -> T): Startup<T> {
    var state: Startup<T> by remember { mutableStateOf<Startup<T>>(Startup.Opening) }

    DisposableEffect(Unit) {
        onDispose { (state as? Startup.Ready)?.value?.close() }
    }

    LaunchedEffect(Unit) {
        // The effect's own job, asked below. `isActive` would not do: inside
        // NonCancellable it is true by construction.
        val effect = checkNotNull(coroutineContext[Job])
        val opened = runCatching { open() }
        // Under NonCancellable, because the window closing while the store is being
        // opened is exactly when this matters. `open()` can return after the effect
        // has been cancelled, and the assignment below would then never run — the
        // SQLite handle open, unreferenced, and `onDispose` looking at a state that
        // never became Ready.
        withContext(NonCancellable) {
            opened
                .onSuccess { value ->
                    if (effect.isActive) state = Startup.Ready(value) else runCatching { value.close() }
                }
                .onFailure { problem ->
                    if (problem !is CancellationException) state = Startup.Failed(problem.toFailure())
                }
        }
    }

    return state
}
