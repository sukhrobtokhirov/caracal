package dev.dbide.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Which key means "this is a command" on the machine the application is running on.
 *
 * Detected once, at class-load, from the same property the rest of the application
 * asks. It is a type rather than a boolean because every use of it — matching a
 * chord, drawing a chord, naming a chord in help — reads better as a `when` over two
 * platforms than as `if (mac)`.
 */
enum class Platform {
    MAC,
    OTHER,
    ;

    companion object {
        val current: Platform =
            if (System.getProperty("os.name").orEmpty().startsWith("Mac")) MAC else OTHER
    }
}

/** The heading a shortcut appears under in help. */
enum class ShortcutGroup(val title: String) {
    QUERY("Running a query"),
    TABS("Query tabs"),
    APPLICATION("Getting around"),
}

/**
 * Every chord the application answers, and the only place any of them is written down.
 *
 * §4.4 asks for two required shortcuts and five recommended ones, for them to be
 * discoverable, and for their labels to be the platform's own. All three of those are
 * the same problem — a chord defined in the handler that runs it and spelled again in
 * the button beside it is a chord that will one day disagree with itself — so a
 * shortcut here is one entry carrying the key, the words, and the group it is filed
 * under, and the toolbar, the tooltips, and the help window all read it.
 *
 * **The command key is the platform's, and only the platform's.** Meta on macOS,
 * Control everywhere else — not "either, on both", which is what the editor's Run
 * chord used to accept. The reason is specific rather than tidiness: Compose's own
 * macOS text-field keymap binds `Ctrl+K` to delete-to-line-end and `Ctrl+Shift+F` to
 * extend-selection, both emacs bindings AppKit has had for decades. A rule that
 * accepted Control as a command modifier on macOS would put this application's
 * connection switcher on a chord the text field under it has already spoken for, and
 * the user would get whichever of the two won — silently, and differently depending
 * on where the caret was. One rule means the chord help names is the chord that runs.
 *
 * [key] and [shift] are what is matched; [stroke] is what is drawn. They are separate
 * because [Key] has no display name worth showing a user.
 */
enum class Shortcut(
    val action: String,
    val detail: String,
    val group: ShortcutGroup,
    internal val key: Key,
    internal val stroke: String,
    internal val shift: Boolean = false,
) {
    RUN(
        action = "Run the statement",
        detail = "Runs the statement the caret is in — the one the editor names above it.",
        group = ShortcutGroup.QUERY,
        key = Key.Enter,
        stroke = "Enter",
    ),
    CANCEL(
        action = "Cancel the running statement",
        detail = "Stops it on the server. Nothing is left half-read.",
        group = ShortcutGroup.QUERY,
        key = Key.Period,
        stroke = ".",
    ),
    NEW_TAB(
        action = "New query tab",
        detail = "Opens an empty tab on the connection in front of you.",
        group = ShortcutGroup.TABS,
        key = Key.T,
        stroke = "T",
    ),
    CLOSE_TAB(
        action = "Close the query tab",
        detail = "Asks first if it holds a script or a statement that is still running.",
        group = ShortcutGroup.TABS,
        key = Key.W,
        stroke = "W",
    ),
    SWITCH(
        action = "Switch connection",
        detail = "Opens the switcher. Type to narrow it, Enter to go there.",
        group = ShortcutGroup.APPLICATION,
        key = Key.K,
        stroke = "K",
    ),
    FIND(
        action = "Focus the key search",
        detail = "On a Redis connection, puts the caret in the key pattern box.",
        group = ShortcutGroup.APPLICATION,
        key = Key.F,
        stroke = "F",
        shift = true,
    ),
    HELP(
        action = "Show shortcuts",
        detail = "This window.",
        group = ShortcutGroup.APPLICATION,
        key = Key.Slash,
        stroke = "/",
    ),
    ;

    /**
     * How this chord is written on [platform].
     *
     * macOS stacks its modifier symbols with nothing between them and puts them in a
     * fixed order; everywhere else spells the words and joins them with `+`. Getting
     * this wrong is not cosmetic — `Ctrl+T` printed on a Mac is an instruction that
     * does not work.
     */
    fun chord(platform: Platform = Platform.current): String = when (platform) {
        Platform.MAC -> buildString {
            if (shift) append('⇧')
            append('⌘')
            append(if (key == Key.Enter) "↵" else stroke)
        }

        Platform.OTHER -> buildString {
            append("Ctrl+")
            if (shift) append("Shift+")
            append(stroke)
        }
    }

    companion object {

        /** Which shortcut [event] is, if it is one. */
        fun of(event: KeyEvent, platform: Platform = Platform.current): Shortcut? {
            if (event.type != KeyEventType.KeyDown) return null
            val command: Boolean
            val foreign: Boolean
            when (platform) {
                Platform.MAC -> {
                    command = event.isMetaPressed
                    foreign = event.isCtrlPressed
                }

                Platform.OTHER -> {
                    command = event.isCtrlPressed
                    foreign = event.isMetaPressed
                }
            }
            // The other platform's command key held as well is not this application's
            // chord — it is a chord for something else, and swallowing it would be
            // this application answering a question it was not asked.
            if (!command || foreign || event.isAltPressed) return null
            return of(event.key, event.isShiftPressed)
        }

        /** The same match, given the two things it actually depends on. */
        internal fun of(key: Key, shift: Boolean): Shortcut? =
            entries.firstOrNull { it.key == key && it.shift == shift }

        /**
         * The shortcuts [query] names, for the reference window's search box.
         *
         * The chord is searched as well as the words, and on the platform it is drawn
         * for: someone who has just been surprised by a key is here to ask what it
         * was, and "what does ⌘K do" is a more common question than "what opens the
         * switcher".
         */
        fun search(query: String, platform: Platform = Platform.current): List<Shortcut> {
            val needle = query.trim()
            if (needle.isEmpty()) return entries
            return entries.filter {
                it.action.contains(needle, ignoreCase = true) ||
                    it.detail.contains(needle, ignoreCase = true) ||
                    it.chord(platform).contains(needle, ignoreCase = true)
            }
        }
    }
}

/**
 * The keyboard's half of the application, and the one thing that knows where a chord
 * goes.
 *
 * Built like [ExitGuard] and for the same reason: a key event arrives from a window,
 * which is a thing with no opinion about tabs or connections, and the screen that
 * does have one is somewhere underneath. The window hands the event here, the
 * workspace registers what to do with it, and neither has to know about the other.
 *
 * [press] exists separately from [dispatch] so that "what does this shortcut do" can
 * be asserted without synthesising a key event, and so that a button and its chord
 * can be wired to literally the same call.
 */
class Shortcuts(val platform: Platform = Platform.current) {

    private var bound: (Shortcut) -> Boolean = { false }

    /**
     * Registers what the shortcuts mean right now.
     *
     * Re-registered on every composition rather than once, because the answer depends
     * on state that changes — which connection is in front, whether a dialog is open —
     * and a handler captured once is a handler answering last hour's question.
     */
    fun bind(handler: (Shortcut) -> Boolean) {
        bound = handler
    }

    /** Performs [shortcut], answering whether anything took it. */
    fun press(shortcut: Shortcut): Boolean = bound(shortcut)

    /** The transport: a raw key event, matched and delivered. */
    fun dispatch(event: KeyEvent): Boolean = Shortcut.of(event, platform)?.let(::press) ?: false

    /** How [shortcut] is written on this machine. */
    fun chord(shortcut: Shortcut): String = shortcut.chord(platform)
}

/**
 * A request for the keyboard, made by something that cannot see whether what it wants
 * is on screen yet.
 *
 * §4.5 asks that choosing a connection move focus to the editor or the key browser it
 * lands in, and the thing making that decision — the switcher — is a dialog that is
 * about to close. It cannot call `FocusRequester.requestFocus()` itself: the pane it
 * means may not exist yet, because the connection is still being opened, and
 * requesting focus on an unattached requester throws.
 *
 * So the request is left here instead, and the pane picks it up when it appears.
 * It is consumed on the way, which is what stops it from firing a second time when
 * the user later switches back to a tab that has been on screen all along.
 */
class FocusRequest {

    var pending: Boolean by mutableStateOf(false)
        private set

    /** Asks for the keyboard, whenever whatever answers this turns up. */
    fun raise() {
        pending = true
    }

    /** Whether this request is outstanding, taking it if it is. */
    fun consume(): Boolean {
        val outstanding = pending
        pending = false
        return outstanding
    }
}
