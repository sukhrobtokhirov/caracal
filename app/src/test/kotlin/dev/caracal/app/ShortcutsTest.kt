package dev.caracal.app

import androidx.compose.ui.input.key.Key
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The chord table itself: what matches, what does not, and what each one is called on
 * the machine reading it.
 *
 * The half that needs a real key event — that a chord pressed in a live editor reaches
 * the workspace rather than the text field — is [dev.caracal.app.ui.ShortcutsUiTest].
 * What is here is everything that can be decided without a window, which is most of
 * it, and it is here rather than there because a table of seven entries deserves
 * assertions that run in milliseconds.
 */
class ShortcutsTest {

    // --- The table ------------------------------------------------------------

    @Test
    fun `every shortcut is a distinct chord`() {
        val chords = Shortcut.entries.map { it.key to it.shift }
        assertEquals(chords.size, chords.toSet().size, "two shortcuts share a chord")
    }

    @Test
    fun `the two shortcuts the milestone requires are the ones it names`() {
        assertEquals(Shortcut.RUN, Shortcut.of(Key.Enter, shift = false))
        assertEquals(Shortcut.SWITCH, Shortcut.of(Key.K, shift = false))
    }

    @Test
    fun `shift is part of the chord rather than ignored`() {
        assertEquals(Shortcut.FIND, Shortcut.of(Key.F, shift = true))
        // Plain Command-F is nothing here. It must not fall through to the shifted
        // entry, or a user reaching for a find they do not have would move the caret
        // into a pane they were not looking at.
        assertNull(Shortcut.of(Key.F, shift = false))
        assertNull(Shortcut.of(Key.T, shift = true))
    }

    @Test
    fun `a key with no chord is not a shortcut`() {
        assertNull(Shortcut.of(Key.A, shift = false))
    }

    // --- How they are written -------------------------------------------------

    @Test
    fun `chords are spelled the way the platform spells them`() {
        assertEquals("⌘↵", Shortcut.RUN.chord(Platform.MAC))
        assertEquals("Ctrl+Enter", Shortcut.RUN.chord(Platform.OTHER))

        assertEquals("⌘K", Shortcut.SWITCH.chord(Platform.MAC))
        assertEquals("Ctrl+K", Shortcut.SWITCH.chord(Platform.OTHER))

        assertEquals("⇧⌘F", Shortcut.FIND.chord(Platform.MAC))
        assertEquals("Ctrl+Shift+F", Shortcut.FIND.chord(Platform.OTHER))

        assertEquals("⌘.", Shortcut.CANCEL.chord(Platform.MAC))
        assertEquals("Ctrl+.", Shortcut.CANCEL.chord(Platform.OTHER))
    }

    @Test
    fun `every shortcut says what it does and when`() {
        Shortcut.entries.forEach {
            assertTrue(it.action.isNotBlank(), "${it.name} has no name")
            assertTrue(it.detail.isNotBlank(), "${it.name} has no explanation")
        }
    }

    // --- The reference's search box -------------------------------------------

    @Test
    fun `an empty search is the whole table`() {
        assertEquals(Shortcut.entries, Shortcut.search("", Platform.MAC))
        assertEquals(Shortcut.entries, Shortcut.search("  ", Platform.MAC))
    }

    @Test
    fun `a shortcut can be found by what it does`() {
        assertEquals(listOf(Shortcut.SWITCH), Shortcut.search("switcher", Platform.MAC))
    }

    @Test
    fun `a shortcut can be found by the chord that surprised you`() {
        // The question the reference exists to answer, and it is asked in the
        // platform's own notation, so the same search finds nothing on the other one.
        assertEquals(listOf(Shortcut.SWITCH), Shortcut.search("⌘K", Platform.MAC))
        assertEquals(emptyList(), Shortcut.search("⌘K", Platform.OTHER))
        assertEquals(listOf(Shortcut.SWITCH), Shortcut.search("ctrl+k", Platform.OTHER))
    }

    @Test
    fun `a term nothing matches finds nothing rather than everything`() {
        assertEquals(emptyList(), Shortcut.search("zzz", Platform.MAC))
    }

    // --- Delivery -------------------------------------------------------------

    @Test
    fun `an unbound shortcut is not handled`() {
        assertFalse(Shortcuts().press(Shortcut.RUN))
    }

    @Test
    fun `the last binding wins, because it is the one describing what is on screen`() {
        val shortcuts = Shortcuts()
        shortcuts.bind { false }

        val taken = mutableListOf<Shortcut>()
        shortcuts.bind { taken += it; true }

        assertTrue(shortcuts.press(Shortcut.NEW_TAB))
        assertEquals(listOf(Shortcut.NEW_TAB), taken)
    }

    @Test
    fun `a binding that declines leaves the chord unhandled`() {
        val shortcuts = Shortcuts()
        shortcuts.bind { it == Shortcut.HELP }

        assertTrue(shortcuts.press(Shortcut.HELP))
        assertFalse(shortcuts.press(Shortcut.RUN))
    }

    // --- Focus requests -------------------------------------------------------

    @Test
    fun `a focus request is taken once`() {
        val request = FocusRequest()
        assertFalse(request.consume())

        request.raise()
        assertTrue(request.pending)
        assertTrue(request.consume())
        // The second read is what stops the caret being pulled back into a pane every
        // time the user returns to it.
        assertFalse(request.consume())
        assertFalse(request.pending)
    }
}
