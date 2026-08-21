package dev.dbide.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The question the window asks itself before it closes.
 *
 * §4.3 asks for a warning before shutdown while unsaved scripts exist, and the window
 * is the only place that can ask it — `onCloseRequest` is what the platform's own
 * close button, `⌘Q`, and the window menu all arrive at. What it must not do is know
 * what "unsaved" means, so it does not: the workspace registers [guard] with the
 * question, and this holds the answer and the state of having asked it.
 *
 * Deliberately not a composable. A close request arrives from AWT, at a moment that
 * is nobody's recomposition, and a decision made in one is a decision made too late.
 */
class ExitGuard {

    /** Whether the confirmation is on screen. */
    var pending: Boolean by mutableStateOf(false)
        private set

    private var unsaved: () -> Boolean = { false }

    /** Registers what "there is something to lose" means. */
    fun guard(unsaved: () -> Boolean) {
        this.unsaved = unsaved
    }

    /**
     * Whether the window may close now.
     *
     * `false` raises the confirmation instead. A second request while it is already on
     * screen is not a second question — the user is being asked, and asking twice is
     * how a stack of identical dialogs happens.
     */
    fun mayClose(): Boolean {
        if (!unsaved()) return true
        pending = true
        return false
    }

    /** The user decided to stay. */
    fun dismiss() {
        pending = false
    }
}
