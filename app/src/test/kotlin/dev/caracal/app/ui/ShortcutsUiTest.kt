package dev.caracal.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.caracal.app.ConnectionsViewModel
import dev.caracal.app.EditorTabs
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.HistoryViewModel
import dev.caracal.app.POSTGRES
import dev.caracal.app.Platform
import dev.caracal.app.REDIS
import dev.caracal.app.RedisWorkspace
import dev.caracal.app.SchemaTreeViewModel
import dev.caracal.app.Shortcut
import dev.caracal.app.Shortcuts
import dev.caracal.app.ThemeViewModel
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.ColumnFormat
import dev.caracal.core.result.QueryResult
import dev.caracal.core.vault.VaultState
import dev.caracal.engine.api.EngineId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test

/**
 * §4.4's chords, pressed into the real workspace.
 *
 * The table itself is asserted in [dev.caracal.app.ShortcutsTest]. What can only be
 * asserted here is the half that is about *where* a key goes: that a chord pressed
 * with the caret in the SQL editor reaches the workspace rather than being eaten by
 * the text field, that the same key without the command modifier is just a letter,
 * and that nothing fires while a window is asking the user a question.
 *
 * Every test names its platform rather than reading the host's. A suite that passed
 * on a Mac and silently skipped its own assertions on Linux would be worse than no
 * suite, and this is the one part of the application whose behaviour is deliberately
 * different on the two.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutsUiTest {

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        queryResult = QueryResult(
            columns = listOf(Column("n", "int8", ColumnFormat.NUMBER)),
            rows = listOf(listOf(CellValue.Integer(7))),
            duration = 4.milliseconds,
        )
    }

    /** The unlocked workspace, with one open connection selected. */
    private fun ComposeUiTest.workspace(
        service: FakeConnectionService,
        platform: Platform = Platform.MAC,
        engine: EngineId = POSTGRES,
    ): EditorTabs {
        service.seed(name = "Live", engine = engine, status = RuntimeStatus.OPEN)
        lateinit var tabs: EditorTabs
        setContent {
            val scope = rememberCoroutineScope()
            val connections = remember { ConnectionsViewModel(service, scope) }
            val tree = remember { SchemaTreeViewModel(service, scope) }
            tabs = remember { EditorTabs(service, scope) { null } }
            val theme = remember { ThemeViewModel(null, scope) }
            val redis = remember { RedisWorkspace(service, scope) }
            val history = remember { HistoryViewModel(service, scope) }
            CaracalTheme {
                WorkspaceScreen(
                    connections,
                    tree,
                    tabs,
                    redis,
                    history,
                    theme,
                    onLock = {},
                    shortcuts = remember { Shortcuts(platform) },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("connection-Live").performClick()
        waitForIdle()
        return tabs
    }

    /**
     * Presses [key] with the command modifier [platform] uses, from [at].
     *
     * [at] has to be a field that can hold focus, because that is the situation these
     * chords have to survive: a key pressed with the caret in a text box, arriving at
     * the workspace only because the box did not want it.
     */
    private fun ComposeUiTest.chord(
        key: Key,
        at: String = "editor-text",
        platform: Platform = Platform.MAC,
        shift: Boolean = false,
    ) {
        val command = if (platform == Platform.MAC) Key.MetaLeft else Key.CtrlLeft
        onNodeWithTag(at).requestFocus()
        waitForIdle()
        onNodeWithTag(at).performKeyInput {
            withKeyDown(command) {
                if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key)
            }
        }
        waitForIdle()
    }

    // --- Tabs -----------------------------------------------------------------

    @Test
    fun `the new-tab chord opens a tab on the connection in front`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val tabs = workspace(service())
            assertEquals(1, tabs.tabs.size)

            chord(Key.T)

            assertEquals(2, tabs.tabs.size)
            onNodeWithTag("editor-tab-1").assertIsDisplayed()
        }

    @Test
    fun `the close chord still asks about a script it would lose`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val tabs = workspace(service())
            onNodeWithTag("editor-text").performTextInput("select invoices;")
            waitForIdle()

            chord(Key.W)

            // The chord is the same request the ✕ makes, so it gets the same question.
            onNodeWithTag("close-tab-confirmation").assertIsDisplayed()
            assertEquals(1, tabs.tabs.size)
        }

    // --- Running --------------------------------------------------------------

    @Test
    fun `the run chord runs the statement instead of typing a newline`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val tabs = workspace(service())
            onNodeWithTag("editor-text").performTextInput("select 7;")
            waitForIdle()

            chord(Key.Enter)

            onNodeWithTag("grid-cell-0-0").assertTextEquals("7")
            assertEquals("select 7;", tabs.tabs.single().editor.text.text)
        }

    @Test
    fun `the cancel chord stops the statement on the server`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = service()
            workspace(service)
            onNodeWithTag("editor-text").performTextInput("select pg_sleep(30);")
            waitForIdle()
            // Held open only now: gating it any earlier would stop the connection list
            // from ever being read, and there would be nothing to select.
            service.gate = CompletableDeferred()
            chord(Key.Enter)
            onNodeWithTag("query-running").assertIsDisplayed()

            chord(Key.Period)

            onNodeWithTag("query-cancelled").assertIsDisplayed()
            service.gate?.complete(Unit)
        }

    // --- Windows --------------------------------------------------------------

    @Test
    fun `the switcher chord opens the switcher`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            workspace(service())

            chord(Key.K)

            onNodeWithTag("connection-switcher").assertIsDisplayed()
        }

    @Test
    fun `the help chord opens the reference, and it lists the chords`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            workspace(service())

            chord(Key.Slash)

            onNodeWithTag("shortcuts-window").assertIsDisplayed()
            onNodeWithTag("shortcut-switch").assertIsDisplayed()
        }

    @Test
    fun `the search chord moves the caret into the key browser from another pane`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            workspace(service(), engine = REDIS)
            // The console's command line, which is a text field in a different pane —
            // so the assertion is that the chord moved the caret, not that it left it
            // where it already was.
            onNodeWithTag("workspace-tab-console").performClick()
            waitForIdle()

            chord(Key.F, at = "console-input", shift = true)

            onNodeWithTag("keys-pattern").assertIsFocused()
        }

    @Test
    fun `the reference draws the chords for the machine reading it, and searches them`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            workspace(service(), platform = Platform.MAC)
            chord(Key.Slash)

            // Not "Ctrl+Enter", which on this machine would be an instruction that
            // does not work.
            onNodeWithText("⌘↵").assertIsDisplayed()
            onNodeWithText("Run the statement").assertIsDisplayed()

            onNodeWithTag("shortcuts-search").performTextInput("query tab")
            waitForIdle()
            onNodeWithTag("shortcut-new_tab").assertIsDisplayed()
            onNodeWithTag("shortcut-run").assertDoesNotExist()

            onNodeWithTag("shortcuts-search").performTextInput("zzz")
            waitForIdle()
            onNodeWithTag("shortcuts-no-match").assertIsDisplayed()
        }

    // --- What must not fire ---------------------------------------------------

    @Test
    fun `nothing fires while a window is asking a question`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val tabs = workspace(service())
            chord(Key.K)
            onNodeWithTag("connection-switcher").assertIsDisplayed()

            // §4.4: the keyboard belongs to the window on top. A second chord must not
            // open a tab behind a palette the user is still reading.
            chord(Key.T, at = "switcher-search")

            assertEquals(1, tabs.tabs.size)
            onNodeWithTag("connection-switcher").assertIsDisplayed()
        }

    @Test
    fun `taking the workspace off screen takes its chords with it`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            service.seed(name = "Live", status = RuntimeStatus.OPEN)
            val shortcuts = Shortcuts(Platform.MAC)
            val showing = mutableStateOf(true)
            setContent {
                val scope = rememberCoroutineScope()
                val connections = remember { ConnectionsViewModel(service, scope) }
                val tree = remember { SchemaTreeViewModel(service, scope) }
                val tabs = remember { EditorTabs(service, scope) { null } }
                val theme = remember { ThemeViewModel(null, scope) }
                val redis = remember { RedisWorkspace(service, scope) }
                val history = remember { HistoryViewModel(service, scope) }
                CaracalTheme {
                    if (showing.value) {
                        WorkspaceScreen(
                            connections,
                            tree,
                            tabs,
                            redis,
                            history,
                            theme,
                            onLock = {},
                            shortcuts = shortcuts,
                        )
                    }
                }
            }
            waitForIdle()
            assertTrue(shortcuts.press(Shortcut.HELP))

            // What locking does: the workspace leaves the composition and the same
            // window stays behind it. A binding that outlived it would put a
            // connection switcher over the lock screen.
            showing.value = false
            waitForIdle()

            assertFalse(shortcuts.press(Shortcut.HELP))
        }

    @Test
    fun `the same key without the command modifier is a letter`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val tabs = workspace(service())

            onNodeWithTag("editor-text").performKeyInput { pressKey(Key.T) }
            waitForIdle()

            assertEquals(1, tabs.tabs.size)
        }

    @Test
    fun `on macOS the Control chords belong to the text field, not to this application`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val tabs = workspace(service(), platform = Platform.MAC)

            // Compose's own macOS keymap binds Ctrl+K to delete-to-line-end. If this
            // application answered it as well, the user would get whichever won.
            chord(Key.K, platform = Platform.OTHER)

            onNodeWithTag("connection-switcher").assertDoesNotExist()
            assertEquals(1, tabs.tabs.size)
        }

    // --- The other platform ---------------------------------------------------

    @Test
    fun `off macOS the same chords are Control`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val tabs = workspace(service(), platform = Platform.OTHER)

            chord(Key.T, platform = Platform.OTHER)
            assertEquals(2, tabs.tabs.size)

            // And Meta is then the foreign one, which this application leaves alone.
            chord(Key.T, platform = Platform.MAC)
            assertEquals(2, tabs.tabs.size)
        }
}
