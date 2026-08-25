package dev.caracal.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.ConnectionsViewModel
import dev.caracal.app.EditorTabs
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.FilePicker
import dev.caracal.app.HistoryViewModel
import dev.caracal.app.RedisWorkspace
import dev.caracal.app.SchemaTreeViewModel
import dev.caracal.app.ThemeViewModel
import dev.caracal.core.vault.VaultState
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.EngineId
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 3's acceptance criterion, as a test rather than as a claim.
 *
 * > Adding a `DatabaseEngine` implementation to the classpath makes it appear in the
 * > UI with zero UI code changes.
 *
 * The engine is `:engine-test`'s `LedgerEngine`. It is not on the application's
 * classpath, only on this module's test runtime classpath, and it is registered the
 * way any engine is: one line in `META-INF/services`. Nothing in `app/src/main`
 * mentions it — the last test here is what holds that true — so everything the
 * dialog draws for it, it drew from the declaration.
 *
 * It is deliberately unlike both bundled engines: it opens a file rather than dialing
 * a host, declares no password, and uses the two field kinds neither PostgreSQL nor
 * Redis has. A dialog that had quietly kept a host, a port and a password would fail
 * here rather than three engines from now.
 */
@OptIn(ExperimentalTestApi::class)
class EngineInstallUiTest {

    private val ledger = EngineId("ledger")

    private fun ComposeUiTest.workspace(
        service: FakeConnectionService,
        picker: FilePicker? = null,
    ): ConnectionsViewModel {
        lateinit var model: ConnectionsViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { ConnectionsViewModel(service, scope) }
            val tree = remember { SchemaTreeViewModel(service, scope) }
            val tabs = remember { EditorTabs(service, scope) { null } }
            val theme = remember { ThemeViewModel(null, scope) }
            val redis = remember { RedisWorkspace(service, scope) }
            val history = remember { HistoryViewModel(service, scope) }
            CaracalTheme {
                WorkspaceScreen(model, tree, tabs, redis, history, theme, onLock = {}, choosePath = picker)
            }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.newLedgerConnection(
        service: FakeConnectionService,
        picker: FilePicker? = null,
    ): ConnectionsViewModel {
        val model = workspace(service, picker)
        onNodeWithTag("new-connection").performClick()
        onNodeWithTag("engine-choice-ledger", useUnmergedTree = true).performClick()
        waitForIdle()
        return model
    }

    @Test
    fun `an engine on the classpath is offered in the connection dialog`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            workspace(FakeConnectionService(VaultState.UNLOCKED))

            onNodeWithTag("new-connection").performClick()

            // Its own name and its own summary line, both off the declaration.
            onNodeWithTag("engine-choice-ledger", useUnmergedTree = true).assertExists()
            onNodeWithText("Ledger").assertIsDisplayed()
        }

    @Test
    fun `its form is the one it declared, and nothing the other engines have`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            newLedgerConnection(FakeConnectionService(VaultState.UNLOCKED))

            // The fields it declared, in the kinds it declared them in.
            onNodeWithTag("field-path").assertExists()
            onNodeWithTag("mode-choice-ro").assertExists()
            onNodeWithTag("mode-choice-rw").assertExists()
            onNodeWithTag("field-journal").assertExists()
            onNodeWithText("The file this connection opens. Nothing else is read.").assertIsDisplayed()

            // And nothing else. A dialog that had kept a host, a port or a password
            // for every engine would draw three boxes this engine never asked for.
            onNodeWithTag("field-host").assertDoesNotExist()
            onNodeWithTag("field-port").assertDoesNotExist()
            onNodeWithTag("field-password").assertDoesNotExist()
            onNodeWithTag("tls-choice-disable").assertDoesNotExist()
        }

    @Test
    fun `its own objection reaches the field it is about`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            newLedgerConnection(service)

            onNodeWithTag("field-name").performTextInput("Books")
            onNodeWithTag("field-path").performTextInput("/tmp/books.sqlite")
            onNodeWithTag("save-connection").performClick()
            waitForIdle()

            // The sentence is the engine's, and no part of it is written in `:app`.
            onNodeWithText("A ledger file is named .ledger.").assertIsDisplayed()
            assertTrue(service.calls.none { it == "create" })
        }

    @Test
    fun `a connection to it is saved with the target it described`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            val service = FakeConnectionService(VaultState.UNLOCKED)
            val model = newLedgerConnection(service)

            onNodeWithTag("field-name").performTextInput("Books")
            onNodeWithTag("field-path").performTextClearance()
            onNodeWithTag("field-path").performTextInput("/tmp/books.ledger")
            onNodeWithTag("mode-choice-rw").performClick()
            onNodeWithTag("save-connection").performClick()
            waitUntil { model.connections.isNotEmpty() }

            val saved = model.connections.single().config
            assertEquals(ledger, saved.engineId)
            // A file, not a host and a port — through a form, a draft, and a store
            // that none of them had to be told about.
            assertEquals(ConnectionTarget.File(java.nio.file.Path.of("/tmp/books.ledger")), saved.target)
            assertEquals("rw", saved.settings["mode"])
            assertEquals("true", saved.settings["journal"])
        }

    @Test
    fun `a declared file field is browsable, and what is chosen lands in it`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            // The picker is a parameter for exactly this: the real one is a native
            // modal window parented to the application's, and a test that had to open
            // one would be a test nobody could run.
            var asked: String? = null
            val chosen = java.nio.file.Path.of("/tmp/chosen.ledger")
            newLedgerConnection(FakeConnectionService(VaultState.UNLOCKED)) { field ->
                asked = field.key
                chosen
            }

            onNodeWithTag("browse-path").performClick()
            waitForIdle()

            assertEquals("path", asked, "the button asked for the wrong field, or for none")
            // Compared against the path's own rendering rather than the literal above:
            // what a picker returns is a `Path`, and Windows spells one with
            // backslashes and a drive letter.
            onNodeWithTag("field-path").assertTextContains(chosen.toString())
        }

    @Test
    fun `a cancelled browse leaves what was already typed alone`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            // Opening the browser to look and changing your mind is not a way to clear
            // the field, and a null answer is what a cancelled dialog gives.
            newLedgerConnection(FakeConnectionService(VaultState.UNLOCKED)) { null }

            onNodeWithTag("field-path").performTextClearance()
            onNodeWithTag("field-path").performTextInput("/tmp/typed.ledger")
            onNodeWithTag("browse-path").performClick()
            waitForIdle()

            onNodeWithTag("field-path").assertTextContains("/tmp/typed.ledger")
        }

    @Test
    fun `with nothing to browse with there is no button rather than a dead one`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            // A control that does nothing is worse than one that is not there: the
            // first has to be tried before anyone learns it does not work.
            newLedgerConnection(FakeConnectionService(VaultState.UNLOCKED), picker = null)

            onNodeWithTag("field-path").assertExists()
            onNodeWithTag("browse-path").assertDoesNotExist()
        }

    @Test
    fun `it is listed among the engines this build can talk to`() =
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            workspace(FakeConnectionService(VaultState.UNLOCKED))

            onNodeWithTag("open-settings").performClick()
            onNodeWithTag("settings-engines", useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithTag("engine-ledger", useUnmergedTree = true).assertExists()
        }

    @Test
    fun `nothing in the application names it`() {
        // The criterion is "with zero UI code changes", and the way that claim rots is
        // a special case added later for one engine. `ArchitectureTest` forbids the
        // engine packages; this forbids the name of an engine that is only supposed to
        // exist on a test classpath.
        val mentions = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    "${file.path}:${index + 1}".takeIf { line.contains("ledger", ignoreCase = true) }
                }
            }
            .toList()

        assertTrue(mentions.isEmpty(), "the application names a test-only engine:\n$mentions")
    }

    @Test
    fun `the fake engine is where the test thinks it is`() {
        // The guard on the guards above: every one of them would also pass if the
        // engine were missing from the classpath and the dialog simply drew nothing.
        val installed = dev.caracal.core.engines.Engines.byId(ledger)

        assertIs<dev.caracal.engine.api.DatabaseEngine>(installed)
        assertEquals("Ledger", installed.displayName)
    }
}
