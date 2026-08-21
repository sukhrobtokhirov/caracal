package dev.dbide.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.EditorTabs
import dev.dbide.app.FakeConnectionService
import dev.dbide.app.HistoryViewModel
import dev.dbide.app.Platform
import dev.dbide.app.RedisWorkspace
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.Shortcuts
import dev.dbide.app.ThemeViewModel
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.vault.VaultState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * §4.5's switcher.
 *
 * Two things are being asserted, and only one of them is about speed. The first is
 * that the keyboard alone gets you from the chord to another server. The second is the
 * one that matters more: that going fast does not cost the safety context — the row
 * still says `PROD`, and it still says so after the switch, on the bar behind it.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionSwitcherUiTest {

    private fun service() = FakeConnectionService(VaultState.UNLOCKED)

    /** The switcher on its own, over a list that is handed to it. */
    private fun ComposeUiTest.switcher(connections: List<ConnectionView>): MutableList<ConnectionId> {
        val chosen = mutableListOf<ConnectionId>()
        setContent {
            DbideTheme {
                ConnectionSwitcher(
                    connections = connections,
                    shortcuts = remember { Shortcuts(Platform.MAC) },
                    onChoose = { chosen += it.id },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        return chosen
    }

    private fun ComposeUiTest.type(text: String) {
        onNodeWithTag("switcher-search").performTextInput(text)
        waitForIdle()
    }

    private fun ComposeUiTest.press(key: Key) {
        onNodeWithTag("switcher-search").requestFocus()
        waitForIdle()
        onNodeWithTag("switcher-search").performKeyInput { pressKey(key) }
        waitForIdle()
    }

    // --- What a row says ------------------------------------------------------

    @Test
    fun `a production connection is still marked production inside the switcher`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            val service = service()
            val live = service.seed(name = "Live", environment = Environment.PROD, readOnly = true)
            val local = service.seed(name = "Local", status = RuntimeStatus.OPEN)
            switcher(listOf(live, local))

            // Colour is never the only signal, here least of all: this is the control
            // that makes switching to production take one second.
            onNodeWithTag("environment-prod", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("read-only", useUnmergedTree = true).assertIsDisplayed()
            // And whether there is a client open, which is what decides whether
            // choosing it dials a server or simply goes there.
            onNodeWithTag("status-open", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("status-closed", useUnmergedTree = true).assertIsDisplayed()
        }

    // --- Narrowing ------------------------------------------------------------

    @Test
    fun `typing narrows the list to what was typed`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            val service = service()
            val live = service.seed(name = "Live")
            val staging = service.seed(name = "Staging")
            switcher(listOf(live, staging))

            type("stag")

            onNodeWithText("Staging").assertIsDisplayed()
            onNodeWithTag("switcher-row-0").assertIsDisplayed()
            onNodeWithTag("switcher-row-1").assertDoesNotExist()
        }

    @Test
    fun `a term nothing is called says so rather than going blank`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            val service = service()
            switcher(listOf(service.seed(name = "Live")))

            type("mysql")

            onNodeWithTag("switcher-no-match").assertIsDisplayed()
        }

    // --- The keyboard ---------------------------------------------------------

    @Test
    fun `Enter chooses the highlighted row, and the arrows move it`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            val service = service()
            val live = service.seed(name = "Live")
            val staging = service.seed(name = "Staging")
            val chosen = switcher(listOf(live, staging))

            // The top row is highlighted from the moment it opens, so the chord, three
            // letters, and Enter is one gesture.
            press(Key.Enter)
            assertEquals(listOf(live.id), chosen)

            press(Key.DirectionDown)
            press(Key.Enter)
            assertEquals(listOf(live.id, staging.id), chosen)

            // And it does not walk off either end.
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.Enter)
            assertEquals(listOf(live.id, staging.id, staging.id), chosen)

            press(Key.DirectionUp)
            press(Key.DirectionUp)
            press(Key.Enter)
            assertEquals(listOf(live.id, staging.id, staging.id, live.id), chosen)
        }

    @Test
    fun `Enter on a freshly opened switcher never lands on production`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            val service = service()
            // The saved list is ordered production first, deliberately, so that in a
            // list you read the dangerous servers are never buried. This control is one
            // you act in, where the same ordering would make the default gesture dial
            // production.
            val live = service.seed(name = "Payments", environment = Environment.PROD)
            val staging = service.seed(name = "Payments staging", environment = Environment.STAGING)
            val chosen = switcher(listOf(live, staging))

            press(Key.Enter)

            assertEquals(listOf(staging.id), chosen)
        }

    @Test
    fun `naming production is enough to choose it, because that is saying so`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            val service = service()
            val live = service.seed(name = "Payments", environment = Environment.PROD)
            val staging = service.seed(name = "Ledger staging", environment = Environment.STAGING)
            val chosen = switcher(listOf(live, staging))

            type("paym")
            press(Key.Enter)
            assertEquals(listOf(live.id), chosen)
        }

    @Test
    fun `arrowing onto production chooses it, and the legend says what to do`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            val service = service()
            val live = service.seed(name = "Payments", environment = Environment.PROD)
            val chosen = switcher(listOf(live))

            // Every row is production, so nothing is preselected — and Enter doing
            // nothing would look broken without the line that explains it.
            press(Key.Enter)
            assertEquals(emptyList(), chosen)
            onNodeWithTag("switcher-legend")
                .assertTextEquals("Type a name, or use ↑↓ · Esc to close")

            press(Key.DirectionDown)
            press(Key.Enter)
            assertEquals(listOf(live.id), chosen)
        }

    @Test
    fun `a row can also just be clicked`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            val service = service()
            val live = service.seed(name = "Live")
            val staging = service.seed(name = "Staging")
            val chosen = switcher(listOf(live, staging))

            onNodeWithTag("switcher-row-1").performClick()
            waitForIdle()

            assertEquals(listOf(staging.id), chosen)
        }

    // --- In the workspace -----------------------------------------------------

    @Test
    fun `choosing a closed connection opens it and takes the shell with it`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = service()
            val local = service.seed(name = "Local", status = RuntimeStatus.OPEN)
            val live = service.seed(
                name = "Live",
                environment = Environment.PROD,
                status = RuntimeStatus.CLOSED,
            )
            lateinit var tabs: EditorTabs
            setContent {
                val scope = rememberCoroutineScope()
                val connections = remember { ConnectionsViewModel(service, scope) }
                val tree = remember { SchemaTreeViewModel(service, scope) }
                tabs = remember { EditorTabs(service, scope) { null } }
                val theme = remember { ThemeViewModel(null, scope) }
                val redis = remember { RedisWorkspace(service, scope) }
                val history = remember { HistoryViewModel(service, scope) }
                DbideTheme {
                    WorkspaceScreen(
                        connections,
                        tree,
                        tabs,
                        redis,
                        history,
                        theme,
                        onLock = {},
                        shortcuts = remember { Shortcuts(Platform.MAC) },
                    )
                }
            }
            waitForIdle()
            onNodeWithTag("connection-Local").performClick()
            waitForIdle()
            onNodeWithTag("editor-text").performTextInput("select invoices;")
            waitForIdle()

            onNodeWithTag("editor-text").requestFocus()
            waitForIdle()
            onNodeWithTag("editor-text").performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.K) }
            }
            waitForIdle()
            type("live")
            press(Key.Enter)

            assertTrue(service.calls.contains("open"), "the closed connection was not dialled")
            // The whole point of the red bar: it followed the switch.
            onNodeWithTag("shell-prod").assertIsDisplayed()

            // §4.3's rule survives §4.5. The tab that was pointed at Local is still
            // pointed at Local and still holds what was typed into it — switching is
            // about where new work goes, not about retargeting work already open.
            assertEquals("select invoices;", tabs.of(local.id).single().editor.text.text)
            // And the server just opened gets its own first tab, empty, as opening one
            // from the sidebar does.
            assertEquals("", tabs.of(live.id).single().editor.text.text)
        }
}
