package dev.dbide.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.dbide.app.FakeConnectionService
import dev.dbide.app.RedisConsoleViewModel
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.Environment
import dev.dbide.core.policy.Acknowledgement
import dev.dbide.core.redis.CommandClearance
import dev.dbide.core.redis.CommandConsent
import dev.dbide.core.redis.CommandResult
import dev.dbide.core.redis.RedisBytes
import dev.dbide.core.redis.RedisReply
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import dev.dbide.core.vault.VaultState
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
            DbideTheme { RedisConsole(model) }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.type(text: String) {
        onNodeWithContentDescription("console-input").performTextInput(text)
        waitForIdle()
    }

    private fun ComposeUiTest.run() {
        onNodeWithContentDescription("console-run").performClick()
        waitForIdle()
    }

    @Test
    fun `a command runs and its reply lands in the transcript`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            val service = service()
            console(service)

            onNodeWithContentDescription("console-empty").assertIsDisplayed()
            // Said permanently rather than once: it is the reason this console has no
            // history file, and the moment to read it is before typing an AUTH.
            onNodeWithContentDescription("console-privacy-note").assertIsDisplayed()

            type("PING")
            run()

            onNodeWithContentDescription("console-entry-1").assertIsDisplayed()
            onNodeWithText("PONG").assertIsDisplayed()
        }

    @Test
    fun `a quoted line shows its parse before it runs`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            console(service())

            type("""SET greeting "hello world"""")

            onNodeWithContentDescription("console-parse-preview").assertIsDisplayed()
        }

    @Test
    fun `an unbalanced quote refuses to run`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            console(service())

            type("""SET k "oops""")

            onNodeWithContentDescription("console-parse-error").assertIsDisplayed()
            onNodeWithContentDescription("console-run").assertIsNotEnabled()
        }

    @Test
    fun `a dangerous command opens a question rather than running`() =
        runDesktopComposeUiTest(width = 700, height = 600) {
            val service = service()
            service.confirmations["FLUSHDB"] = confirm("FLUSHDB")
            console(service)

            type("FLUSHDB")
            run()

            onNodeWithContentDescription("command-confirmation").assertIsDisplayed()
            // The guard's own sentence about what the command does — which is the
            // reason this dialog is worth reading rather than dismissing.
            onNodeWithContentDescription("command-warning").assertIsDisplayed()
            onNodeWithContentDescription("command-single-use").assertIsDisplayed()
            onNodeWithContentDescription("console-entry-1").assertDoesNotExist()

            onNodeWithContentDescription("confirm-command").performClick()
            waitForIdle()

            assertEquals(
                listOf(CommandConsent.None, CommandConsent.Given("")),
                service.consents,
            )
            onNodeWithContentDescription("console-entry-1").assertIsDisplayed()
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

            onNodeWithContentDescription("command-prod-banner").assertIsDisplayed()
            onNodeWithContentDescription("confirm-command").assertIsNotEnabled()

            onNodeWithContentDescription("command-acknowledgement").performTextInput("cache")
            waitForIdle()
            // The connection's name alone is what a user retypes by rote. The command
            // is the half that differs between the flush they meant and the one they
            // did not.
            onNodeWithContentDescription("confirm-command").assertIsNotEnabled()

            onNodeWithContentDescription("command-acknowledgement").performTextInput(" FLUSHALL")
            waitForIdle()
            onNodeWithContentDescription("confirm-command").assertIsEnabled()
            onNodeWithContentDescription("confirm-command").performClick()
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
            onNodeWithContentDescription("cancel-command").performClick()
            waitForIdle()

            onNodeWithContentDescription("command-confirmation").assertDoesNotExist()
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

            onNodeWithContentDescription("console-failure").assertIsDisplayed()
            // A button that could do nothing is worse than no button.
            onNodeWithContentDescription("command-confirmation").assertDoesNotExist()
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
            onNodeWithContentDescription("console-failure").assertDoesNotExist()
            onNodeWithContentDescription("console-truncated").assertIsDisplayed()
        }
}
