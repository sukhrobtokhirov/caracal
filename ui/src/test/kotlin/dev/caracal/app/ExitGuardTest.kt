package dev.caracal.app

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The question the window asks before it closes, and when it does not ask it. */
class ExitGuardTest {

    @Test
    fun `a window with nothing to lose closes without asking`() {
        val guard = ExitGuard()
        guard.guard { false }

        assertTrue(guard.mayClose())
        assertFalse(guard.pending)
    }

    @Test
    fun `an unregistered guard does not hold the window open`() {
        // The frame between the window existing and the workspace being composed. A
        // guard that defaulted to "there is something to lose" would make an
        // application that cannot be quit from its own lock screen.
        assertTrue(ExitGuard().mayClose())
    }

    @Test
    fun `an unsaved script stops the close and raises the question once`() {
        val guard = ExitGuard()
        guard.guard { true }

        assertFalse(guard.mayClose())
        assertTrue(guard.pending)

        // ⌘Q pressed twice is not two questions.
        assertFalse(guard.mayClose())
        assertTrue(guard.pending)

        guard.dismiss()
        assertFalse(guard.pending)
    }

    @Test
    fun `the answer is asked again each time, not remembered`() {
        var unsaved = true
        val guard = ExitGuard()
        guard.guard { unsaved }
        assertFalse(guard.mayClose())

        guard.dismiss()
        unsaved = false

        assertTrue(guard.mayClose())
    }
}
