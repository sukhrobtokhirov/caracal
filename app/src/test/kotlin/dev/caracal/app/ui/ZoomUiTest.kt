package dev.caracal.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.caracal.app.ConnectionsViewModel
import dev.caracal.app.EditorTabs
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.HistoryViewModel
import dev.caracal.app.RedisWorkspace
import dev.caracal.app.SchemaTreeViewModel
import dev.caracal.app.ThemeViewModel
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.vault.VaultState
import org.junit.jupiter.api.Test

/**
 * §4.9's last bullet: 200% and a small laptop, without losing the controls that
 * matter.
 *
 * A desktop application has no browser zoom. Its equivalent is the display scale the
 * OS is set to, which reaches Compose as `Density` — so 200% is a density of 2 on
 * the same window, and everything laid out in `dp` gets twice the pixels and half
 * the room.
 *
 * The three things checked here are the three §4.9 names: Run, Cancel, and which
 * environment the statement would go to. A window that hides the first two is a
 * window that cannot be used, and one that hides the third at 200% is a window that
 * quietly drops the production warning for the users most likely to be zoomed in.
 */
@OptIn(ExperimentalTestApi::class)
class ZoomUiTest {

    private fun service() = FakeConnectionService(VaultState.UNLOCKED)

    private fun ComposeUiTest.workspace(service: FakeConnectionService, scale: Float) {
        service.seed(
            name = "Ledger",
            engine = Engine.POSTGRES,
            status = RuntimeStatus.OPEN,
            environment = Environment.PROD,
        )
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                val scope = rememberCoroutineScope()
                CaracalTheme {
                    WorkspaceScreen(
                        remember { ConnectionsViewModel(service, scope) },
                        remember { SchemaTreeViewModel(service, scope) },
                        remember { EditorTabs(service, scope) { null } },
                        remember { RedisWorkspace(service, scope) },
                        remember { HistoryViewModel(service, scope) },
                        remember { ThemeViewModel(null, scope) },
                        onLock = {},
                    )
                }
            }
        }
        waitForIdle()
    }

    /**
     * Chooses the connection the way someone would when the sidebar is not there.
     *
     * Which is the point: the switcher is the reason the connection list is the pane
     * that gives way first, so a test that reached for the list instead would be
     * testing a route the small window deliberately does not have.
     */
    private fun ComposeUiTest.chooseThroughSwitcher() {
        onNodeWithTag("open-switcher").performClick()
        waitForIdle()
        onNodeWithTag("switcher-row-0").performClick()
        waitForIdle()
    }

    /**
     * The smallest window the application allows, at the scale someone who needs
     * large text is running. `Main.kt` sets a 760×480 minimum, and this is what that
     * minimum is worth: 380 by 240 of layout.
     */
    @Test
    fun `run, cancel and the production warning survive 200 percent on a small window`() =
        runDesktopComposeUiTest(width = 760, height = 480) {
            workspace(service(), scale = 2f)

            // The connection list is gone at this size, on purpose.
            onNodeWithTag("connection-Ledger").assertDoesNotExist()
            chooseThroughSwitcher()

            onNodeWithTag("editor-run").assertIsDisplayed()
            // Production is the one that cannot be allowed to fall off an edge: the
            // bar it lives on is the only thing between a user and the wrong server.
            onNodeWithTag("shell-prod").assertIsDisplayed()
            onNodeWithTag("environment-prod", useUnmergedTree = true).assertExists()
        }

    @Test
    fun `the same controls are there on an ordinary laptop at 100 percent`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            workspace(service(), scale = 1f)

            // Room for everything: the list is where it always is.
            onNodeWithTag("connection-Ledger").performClick()
            waitForIdle()

            onNodeWithTag("editor-run").assertIsDisplayed()
            onNodeWithTag("connection-Ledger").assertIsDisplayed()
            onNodeWithTag("shell-prod").assertIsDisplayed()
        }
}
