package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.core.store.StoreOpenException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The window's one long-lived resource.
 *
 * The first test here is a regression: the application shipped a `DisposableEffect`
 * keyed on the resource it was guarding, so the store was closed the instant it
 * finished opening, and every screen after it failed with an unrecognised error.
 */
@OptIn(ExperimentalTestApi::class)
class StartupTest {

    private class Resource : AutoCloseable {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the resource stays open once it has been opened`() =
        runDesktopComposeUiTest {
            val resource = Resource()
            lateinit var startup: Startup<Resource>
            setContent { startup = rememberStartup { resource } }

            waitUntil { startup is Startup.Ready }
            waitForIdle()

            // The bug this guards: opening the resource must not immediately close it.
            assertFalse(resource.closed, "the resource was closed as soon as it opened")
            assertEquals(resource, assertIs<Startup.Ready<Resource>>(startup).value)
        }

    @Test
    fun `the resource is closed when the composition goes away`() =
        runDesktopComposeUiTest {
            val resource = Resource()
            var mounted by mutableStateOf(true)
            setContent { if (mounted) rememberStartup { resource } }
            waitForIdle()
            assertFalse(resource.closed)

            mounted = false
            waitForIdle()

            assertTrue(resource.closed, "closing the window must release the resource")
        }

    @Test
    fun `a resource that will not open becomes a classified failure`() =
        runDesktopComposeUiTest {
            lateinit var startup: Startup<Resource>
            setContent {
                startup = rememberStartup<Resource> {
                    throw StoreOpenException("The configuration database is unreadable.")
                }
            }

            waitUntil { startup is Startup.Failed }

            assertEquals("store_unavailable", assertIs<Startup.Failed>(startup).failure.code)
        }

    @Test
    fun `it opens exactly once, however often the window recomposes`() =
        runDesktopComposeUiTest {
            var opens = 0
            var tick by mutableStateOf(0)
            lateinit var startup: Startup<Resource>
            setContent {
                // Read the state so a change forces a recomposition.
                @Suppress("UNUSED_EXPRESSION")
                tick
                startup = rememberStartup { Resource().also { opens++ } }
            }
            waitUntil { startup is Startup.Ready }

            repeat(3) { tick++ }
            waitForIdle()

            assertEquals(1, opens)
        }
}
