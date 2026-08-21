package dev.caracal.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.RedisConsoleViewModel
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Environment
import dev.caracal.core.policy.Acknowledgement
import dev.caracal.core.redis.CommandClearance
import dev.caracal.core.redis.CommandConsent
import dev.caracal.core.redis.CommandResult
import dev.caracal.core.redis.RedisBytes
import dev.caracal.core.redis.RedisReply
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.vault.VaultState
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Test

/**
 * The console through the real composables.
 *
 * The guard's own behaviour is `:core`'s and is tested there against a real server.
 * What is asserted here is the half a screen can get wrong: that a question is a
 * dialog and not a result, that a production phrase cannot be clicked past, and that a
 * refusal with no override does not offer one.
 */
@OptIn(ExperimentalTestApi::class)
class RedisConsoleUiTest {

    private val id = ConnectionId("id-1")

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        commandResult = CommandResult(
            command = "PING",
            reply = RedisReply.Status("PONG"),
            duration = 1.milliseconds,
            truncated = false,
        )
    }

    private fun confirm(
        command: String,
        acknowledgement: Acknowledgement = Acknowledgement.CLICK,
        environment: Environment = Environment.DEV,
    ) = CommandClearance.Confirm(
        command = command,
        acknowledgement = acknowledgement,
        connectionName = "cache",
        environment = environment,
        warning = "$command discards every key in the database.",
    )

    private fun ComposeUiTest.console(service: FakeConnectionService): RedisConsoleViewModel {
        lateinit var model: RedisConsoleViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { RedisConsoleViewModel(service, scope) }
            LaunchedEffect(Unit) { model.show(id) }
            CaracalTheme { RedisConsole(model) }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.type(text: String) {
        onNodeWithTag("console-input").performTextInput(text)
        waitForIdle()
    }

    private fun ComposeUiTest.run() {
        onNodeWithTag("console-run").performClick()
        waitForIdle()
    }

    @Test
    fun `a command runs and its reply lands in the transcript`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            val service = service()
            console(service)

            onNodeWithTag("console-empty").assertIsDisplayed()
            // Said permanently rather than once: it is the reason this console has no
            // history file, and the moment to read it is before typing an AUTH.
            onNodeWithTag("console-privacy-note").assertIsDisplayed()

            type("PING")
            run()

            onNodeWithTag("console-entry-1").assertIsDisplayed()
            onNodeWithText("PONG").assertIsDisplayed()
        }

    @Test
    fun `a quoted line shows its parse before it runs`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            console(service())

            type("""SET greeting "hello world"""")

            onNodeWithTag("console-parse-preview").assertIsDisplayed()
        }

    @Test
    fun `an unbalanced quote refuses to run`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            console(service())

            type("""SET k "oops""")

            onNodeWithTag("console-parse-error").assertIsDisplayed()
            onNodeWithTag("console-run").assertIsNotEnabled()
        }

    @Test
    fun `a dangerous command opens a question rather than running`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            val service = service()
            service.confirmations["FLUSHDB"] = confirm("FLUSHDB")
            console(service)

            type("FLUSHDB")
            run()

            onNodeWithTag("command-confirmation").assertIsDisplayed()
            // The guard's own sentence about what the command does — which is the
            // reason this dialog is worth reading rather than dismissing.
            onNodeWithTag("command-warning").assertIsDisplayed()
            onNodeWithTag("command-single-use").assertIsDisplayed()
            onNodeWithTag("console-entry-1").assertDoesNotExist()

            onNodeWithTag("confirm-command").performClick()
            waitForIdle()

            assertEquals(
                listOf(CommandConsent.None, CommandConsent.Given("")),
                service.consents,
            )
            onNodeWithTag("console-entry-1").assertIsDisplayed()
        }

    @Test
    fun `a production command cannot be run until the phrase is typed`() =
        runDesktopComposeUiTest(width = 700, height = 700) {
            val service = service()
            service.confirmations["FLUSHALL"] =
                confirm("FLUSHALL", Acknowledgement.TYPED, Environment.PROD)
            console(service)

            type("FLUSHALL")
            run()

            onNodeWithTag("command-prod-banner").assertIsDisplayed()
            onNodeWithTag("confirm-command").assertIsNotEnabled()

            onNodeWithTag("command-acknowledgement").performTextInput("cache")
            waitForIdle()
            // The connection's name alone is what a user retypes by rote. The command
            // is the half that differs between the flush they meant and the one they
            // did not.
            onNodeWithTag("confirm-command").assertIsNotEnabled()

            onNodeWithTag("command-acknowledgement").performTextInput(" FLUSHALL")
            waitForIdle()
            onNodeWithTag("confirm-command").assertIsEnabled()
            onNodeWithTag("confirm-command").performClick()
            waitForIdle()

            assertEquals(CommandConsent.Given("cache FLUSHALL"), service.consents.last())
        }

    @Test
    fun `cancelling a question sends nothing`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            val service = service()
            service.confirmations["KEYS"] = confirm("KEYS")
            console(service)

            type("KEYS *")
            run()
            onNodeWithTag("cancel-command").performClick()
            waitForIdle()

            onNodeWithTag("command-confirmation").assertDoesNotExist()
            assertEquals(listOf<CommandConsent>(CommandConsent.None), service.consents)
        }

    @Test
    fun `a read-only refusal offers no override`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            val service = service()
            service.nextFailure = DbException(
                DbError.CommandNotAllowed(
                    message = "This connection is read only, and DEL is not one of the " +
                        "commands known to only read.",
                    command = "DEL",
                    reason = DbError.CommandNotAllowed.Reason.READ_ONLY,
                ),
            )
            console(service)

            type("DEL user:42")
            run()

            onNodeWithTag("console-failure").assertIsDisplayed()
            // A button that could do nothing is worse than no button.
            onNodeWithTag("command-confirmation").assertDoesNotExist()
        }

    @Test
    fun `a nested error is a value and a truncated reply says so`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            val service = service()
            service.commandResult = CommandResult(
                command = "EXEC",
                reply = RedisReply.Items(
                    kind = RedisReply.Items.Kind.ARRAY,
                    items = listOf(
                        RedisReply.Status("OK"),
                        RedisReply.Failure("WRONGTYPE Operation against a key"),
                        RedisReply.Bulk(RedisBytes.of("payload".toByteArray(), 4096)),
                    ),
                    truncated = false,
                ),
                duration = 2.milliseconds,
                truncated = true,
            )
            console(service)

            type("EXEC")
            run()

            // An error inside an array is not the command's failure: the reply arrived
            // intact and one of its elements is an error.
            onNodeWithText("2) (error) WRONGTYPE Operation against a key").assertIsDisplayed()
            onNodeWithTag("console-failure").assertDoesNotExist()
            onNodeWithTag("console-truncated").assertIsDisplayed()
        }
}
