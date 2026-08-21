package dev.caracal.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.EditorViewModel
import dev.caracal.app.ExportViewModel
import dev.caracal.app.FakeConnectionService
import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.vault.VaultState
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * §2.4's gate, driven the way a person drives it.
 *
 * The view-model tests already prove the rule — what the policy decides, and that a
 * production write is not released without the typed name. What only a rendered
 * dialog can prove is the part that keeps the rule honest in practice: that the
 * button is genuinely unavailable until the word matches, that the statement about
 * to run is on screen, and that the production banner is inside the dialog rather
 * than behind the scrim that has just dimmed it.
 */
@OptIn(ExperimentalTestApi::class)
class WriteConfirmationUiTest {

    private fun service() = FakeConnectionService(VaultState.UNLOCKED)

    private fun ComposeUiTest.pane(
        service: FakeConnectionService,
        connection: ConnectionConfig,
    ): EditorViewModel {
        lateinit var model: EditorViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { EditorViewModel(service, scope).also { it.show(connection) } }
            val export = remember { ExportViewModel(service, scope) { null } }
            CaracalTheme { QueryPane(model, export, onCopy = {}) }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.type(sql: String) {
        onNodeWithTag("editor-text").performTextInput(sql)
        waitForIdle()
    }

    private fun ComposeUiTest.run() {
        onNodeWithTag("editor-run").performClick()
        waitForIdle()
    }

    // --- Development and staging: one click -----------------------------------

    @Test
    fun `a write on a development connection asks before it runs`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service, connection())
            type("delete from invoices;")

            run()

            onNodeWithTag("write-confirmation").assertIsDisplayed()
            // What is about to run, on screen. A confirmation that does not show its
            // statement is asking the user to trust their memory of where the caret was.
            onNodeWithTag("write-statement")
                .assertTextContains("delete from invoices;")
            assertEquals(emptyList(), service.executed)
        }

    @Test
    fun `clicking through the confirmation sends the statement`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service, connection())
            type("delete from invoices;")
            run()

            onNodeWithTag("confirm-write").performClick()
            waitForIdle()

            assertEquals(listOf("delete from invoices;"), service.executed)
        }

    @Test
    fun `cancelling the confirmation sends nothing and closes it`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            val model = pane(service, connection())
            type("drop table invoices;")
            run()

            onNodeWithTag("cancel-write").performClick()
            waitForIdle()

            assertEquals(emptyList(), service.executed)
            assertEquals(null, model.pending)
            // Back to a usable editor, not a stuck one.
            onNodeWithTag("editor-run").assertIsEnabled()
        }

    @Test
    fun `an unrecognized statement says so rather than calling itself a write`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service, connection())
            type("frobnicate invoices;")

            run()

            onNodeWithTag("write-explanation")
                .assertTextContains("not recognized", substring = true)
        }

    // --- Production: the name, typed ------------------------------------------

    @Test
    fun `a production write cannot be run until the connection's name is typed`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service, connection(name = "payments-prod", environment = Environment.PROD))
            type("update invoices set total = 0;")
            run()

            // The banner is inside the dialog, because the shell's one is behind a scrim.
            onNodeWithTag("write-prod-banner").assertIsDisplayed()
            onNodeWithTag("confirm-write").assertIsNotEnabled()

            onNodeWithTag("write-acknowledgement").performTextInput("payments")
            waitForIdle()
            onNodeWithTag("confirm-write").assertIsNotEnabled()

            onNodeWithTag("write-acknowledgement").performTextInput("-prod")
            waitForIdle()
            onNodeWithTag("confirm-write").assertIsEnabled()

            assertEquals(emptyList(), service.executed)
        }

    @Test
    fun `a production write runs once the name matches`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service, connection(name = "payments-prod", environment = Environment.PROD))
            type("update invoices set total = 0;")
            run()

            onNodeWithTag("write-acknowledgement").performTextInput("payments-prod")
            waitForIdle()
            onNodeWithTag("confirm-write").performClick()
            waitForIdle()

            assertEquals(listOf("update invoices set total = 0;"), service.executed)
        }

    @Test
    fun `a staging write needs no typing`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service, connection(name = "staging", environment = Environment.STAGING))
            type("delete from invoices;")
            run()

            onNodeWithTag("confirm-write").assertIsEnabled()
        }

    // --- Read-only: no dialog at all ------------------------------------------

    @Test
    fun `a write on a read-only connection is refused instead of being offered`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service, connection(readOnly = true))
            type("delete from invoices;")

            run()

            // No dialog: there is nothing for a confirmation to unlock, and offering
            // one would be offering something the pool would refuse anyway.
            onNodeWithTag("failure-read_only_connection").assertIsDisplayed()
            assertEquals(emptyList(), service.executed)
        }

    @Test
    fun `a read on a production connection runs without a confirmation`() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val service = service()
            pane(service, connection(name = "payments-prod", environment = Environment.PROD))
            type("select * from invoices;")

            run()

            assertEquals(listOf("select * from invoices;"), service.executed)
        }

    private fun connection(
        name: String = "local",
        environment: Environment = Environment.DEV,
        readOnly: Boolean = false,
    ) = ConnectionConfig(
        id = ConnectionId("id-1"),
        name = name,
        engine = Engine.POSTGRES,
        host = "localhost",
        port = 5432,
        database = "caracal",
        username = "caracal",
        tlsMode = TlsMode.DISABLE,
        environment = environment,
        readOnly = readOnly,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )
}
