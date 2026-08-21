package dev.dbide.app

import dev.dbide.app.ui.ThemeMode
import dev.dbide.core.vault.MetadataStore
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The one preference this application keeps, and the ways it can go wrong.
 *
 * The interesting cases are all failures. A settings file is the part of an
 * application most likely to be hand-edited, deleted, or written by a version that
 * no longer exists, and none of those may be the reason a database client refuses to
 * open — the theme is the least important thing in the process and it must never be
 * the thing that stops it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelTest {

    /** A store that can be told to hold something, to be empty, or to fail. */
    private class FakeStore(
        private var stored: ByteArray? = null,
        private val failsOnRead: Boolean = false,
        private val failsOnWrite: Boolean = false,
    ) : MetadataStore {
        var writes = 0
            private set

        val saved: String? get() = stored?.decodeToString()

        override suspend fun getMetadata(key: String): ByteArray? {
            if (failsOnRead) error("the configuration database is unreadable")
            return if (key == ThemeViewModel.KEY) stored else null
        }

        override suspend fun putMetadata(key: String, value: ByteArray) {
            writes++
            if (failsOnWrite) error("the disk is full")
            if (key == ThemeViewModel.KEY) stored = value
        }
    }

    @Test
    fun `a saved preference is what the window opens in`() = runTest {
        val store = FakeStore("light".encodeToByteArray())

        assertEquals(ThemeMode.LIGHT, ThemeViewModel.read(store))
    }

    @Test
    fun `a first launch opens in the default`() = runTest {
        assertEquals(ThemeMode.DEFAULT, ThemeViewModel.read(FakeStore()))
    }

    /**
     * The forward-compatibility case: a preference written by a later version, read
     * by this one. It is not an error and it is not a prompt; it is the default.
     */
    @Test
    fun `a preference this version does not recognise is the default`() = runTest {
        assertEquals(ThemeMode.DEFAULT, ThemeViewModel.read(FakeStore("solarized".encodeToByteArray())))
    }

    @Test
    fun `a store that cannot be read still opens the window`() = runTest {
        assertEquals(ThemeMode.DEFAULT, ThemeViewModel.read(FakeStore(failsOnRead = true)))
    }

    @Test
    fun `choosing a theme applies it before it is saved`() = runTest {
        val store = FakeStore()
        val model = ThemeViewModel(store, TestScope(testScheduler))

        model.select(ThemeMode.LIGHT)

        // The frame does not wait for the disk. This is the assertion that the
        // setting is applied synchronously and persisted afterwards, not the reverse.
        assertEquals(ThemeMode.LIGHT, model.mode)
        assertNull(store.saved)

        advanceUntilIdle()
        assertEquals("light", store.saved)
    }

    @Test
    fun `a save that fails costs the next launch, not this click`() = runTest {
        val model = ThemeViewModel(FakeStore(failsOnWrite = true), TestScope(testScheduler))

        model.select(ThemeMode.LIGHT)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, model.mode)
    }

    /** Without a store — the frames before one has been opened — nothing is written. */
    @Test
    fun `a theme chosen with nowhere to save it is still applied`() = runTest {
        val model = ThemeViewModel(null, TestScope(testScheduler))

        model.select(ThemeMode.LIGHT)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, model.mode)
    }

    @Test
    fun `the toggle walks every theme and comes back`() = runTest {
        val store = FakeStore()
        val model = ThemeViewModel(store, TestScope(testScheduler), initial = ThemeMode.SYSTEM)

        val walked = ThemeMode.entries.map {
            model.cycle()
            model.mode
        }

        assertEquals(ThemeMode.entries.size, walked.toSet().size, "the toggle skipped a theme")
        assertEquals(ThemeMode.SYSTEM, model.mode, "the toggle did not come back round")
        advanceUntilIdle()
        assertEquals(ThemeMode.entries.size, store.writes)
    }

    @Test
    fun `a store handed over later is read on request`() = runTest {
        val model = ThemeViewModel(FakeStore("light".encodeToByteArray()), TestScope(testScheduler))
        assertEquals(ThemeMode.DEFAULT, model.mode)

        model.load()
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, model.mode)
    }
}
