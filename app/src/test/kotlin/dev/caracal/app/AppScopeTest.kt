package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test

/**
 * The scope every view model in the window is launched from.
 *
 * The first test here is a regression, and it is the one that matters. The window
 * built this scope by handing a `SupervisorJob()` to `rememberCoroutineScope` as
 * context — which that function refuses, by returning a scope whose job has already
 * completed exceptionally rather than by throwing. Nothing failed loudly: every
 * `launch` simply did nothing. The vault never read its own state, the screen never
 * left `VaultUiState.Loading`, and because that state deliberately draws nothing,
 * the application opened as an empty rectangle of the theme's background colour with
 * an empty stderr behind it.
 *
 * A scope that silently swallows work is invisible in every test that builds its own
 * scope, which is every other UI test in this package. So it is asserted here
 * directly: work handed to this scope has to actually run.
 */
@OptIn(ExperimentalTestApi::class)
class AppScopeTest {

    @Test
    fun `work launched on the window's scope actually runs`() =
        runDesktopComposeUiTest {
            lateinit var scope: CoroutineScope
            setContent { scope = rememberSupervisorScope() }
            waitForIdle()

            val ran = CompletableDeferred<Unit>()
            scope.launch { ran.complete(Unit) }

            // The bug this guards: the scope was dead on arrival, so `launch`
            // returned a job that never started and never reported anything.
            waitUntil("the coroutine ran") { ran.isCompleted }
        }

    @Test
    fun `one view model failing does not silence the others`() =
        runDesktopComposeUiTest {
            lateinit var scope: CoroutineScope
            setContent { scope = rememberSupervisorScope() }
            waitForIdle()

            scope.launch { throw IllegalStateException("one view model fell over") }
            waitForIdle()

            // The supervisor's whole point: the scope is still usable afterwards.
            val survived = CompletableDeferred<Unit>()
            scope.launch { survived.complete(Unit) }
            waitUntil("the scope still works after a child failed") { survived.isCompleted }
        }

    @Test
    fun `the scope is cancelled when the window goes away`() =
        runDesktopComposeUiTest {
            lateinit var scope: CoroutineScope
            var mounted by mutableStateOf(true)
            setContent { if (mounted) scope = rememberSupervisorScope() }
            waitForIdle()

            val started = AtomicBoolean(false)
            scope.launch {
                started.set(true)
                CompletableDeferred<Unit>().await() // never completes
            }
            waitUntil("the coroutine started") { started.get() }
            assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)

            mounted = false
            waitForIdle()

            // Grafting the supervisor on must not orphan it from the composition:
            // leaving the composition still has to tear its children down.
            assertFalse(
                scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive,
                "the scope outlived the composition that owns it",
            )
        }
}
