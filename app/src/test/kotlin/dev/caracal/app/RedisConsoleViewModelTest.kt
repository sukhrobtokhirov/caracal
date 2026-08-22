package dev.caracal.app

import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Environment
import dev.caracal.core.policy.Acknowledgement
import dev.caracal.core.policy.CommandClearance
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.vault.VaultState
import dev.caracal.engine.api.CommandConsent
import dev.caracal.engine.api.TextValue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The console: what it parses, what it sends, and what it refuses to remember.
 *
 * The consent assertions are the ones worth having. §3.10's rule is that an override
 * applies to one command and is never saved, and the only way to prove that from
 * outside is to run a second command and read what consent *it* arrived with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RedisConsoleViewModelTest {

    private val id = ConnectionId("id-1")

    private fun service() = FakeConnectionService(VaultState.UNLOCKED)

    private fun TestScope.model(service: FakeConnectionService): RedisConsoleViewModel =
        RedisConsoleViewModel(service, this).also { it.show(id) }

    private fun confirm(
        command: String,
        acknowledgement: Acknowledgement = Acknowledgement.CLICK,
        environment: Environment = Environment.DEV,
    ) = CommandClearance.Confirm(
        command = command,
        acknowledgement = acknowledgement,
        connectionName = "cache",
        environment = environment,
        warning = "$command is dangerous.",
    )

    @Test
    fun `an ordinary line needs no parse preview`() = runTest {
        val model = model(service())

        model.edit("GET user:42")

        val parsed = assertIs<ParsedLine.Ready>(model.parsed)
        assertFalse(parsed.ambiguous)
        assertTrue(model.runnable)
    }

    @Test
    fun `a quoted line shows what it will actually send`() = runTest {
        val model = model(service())

        model.edit("""SET greeting "hello world"""")

        val parsed = assertIs<ParsedLine.Ready>(model.parsed)
        // Three arguments, not four — which is the whole reason §3.9 asks for the
        // parse to be visible before the key is written rather than after.
        assertTrue(parsed.ambiguous)
        assertEquals(
            listOf("SET", "greeting", "hello world"),
            parsed.arguments.map { (it as TextValue.Utf8).value },
        )
    }

    @Test
    fun `an unclosed quote is refused before anything is sent`() = runTest {
        val service = service()
        val model = model(service)

        model.edit("""SET k "unterminated""")
        model.run()
        advanceUntilIdle()

        assertIs<ParsedLine.Invalid>(model.parsed)
        assertFalse(model.runnable)
        assertTrue(service.calls.none { it.startsWith("runCommand") })
    }

    @Test
    fun `running a command records it by name and clears the line`() = runTest {
        val service = service()
        val model = model(service)
        model.edit("PING")

        model.run()
        advanceUntilIdle()

        assertEquals(listOf("PING"), model.entries.map { it.label })
        assertEquals("", model.line)
        // The consent an unguarded command carries is none at all.
        assertEquals(listOf<CommandConsent>(CommandConsent.None), service.consents)
    }

    @Test
    fun `a dangerous command asks first, and the agreement covers only that command`() = runTest {
        val service = service()
        service.confirmations["FLUSHDB"] = confirm("FLUSHDB")
        val model = model(service)
        model.edit("FLUSHDB")

        model.run()
        advanceUntilIdle()

        // Nothing ran and nothing is in the transcript: the question is not an outcome.
        assertEquals("FLUSHDB", model.pending?.clearance?.command)
        assertTrue(model.entries.isEmpty())

        model.confirm()
        advanceUntilIdle()

        assertNull(model.pending)
        assertEquals(listOf("FLUSHDB"), model.entries.map { it.label })

        // The next command carries no consent at all. There is nowhere for the last
        // one to have been stored, which is §3.10's single-use rule made structural.
        service.confirmations["FLUSHDB"] = confirm("FLUSHDB")
        model.edit("FLUSHDB")
        model.run()
        advanceUntilIdle()

        assertEquals("FLUSHDB", model.pending?.clearance?.command)
        assertEquals(
            listOf(CommandConsent.None, CommandConsent.Given(""), CommandConsent.None),
            service.consents,
        )
    }

    @Test
    fun `a production command is not sent until the phrase matches`() = runTest {
        val service = service()
        service.confirmations["FLUSHALL"] =
            confirm("FLUSHALL", Acknowledgement.TYPED, Environment.PROD)
        val model = model(service)
        model.edit("FLUSHALL")
        model.run()
        advanceUntilIdle()

        model.confirm("cache")
        advanceUntilIdle()

        // A phrase that is only the connection's name is the one a user retypes by
        // rote. The command is the half that differs between the flush they meant and
        // the one they did not.
        assertEquals("FLUSHALL", model.pending?.clearance?.command)
        assertEquals(1, service.consents.size)

        model.confirm("cache FLUSHALL")
        advanceUntilIdle()

        assertNull(model.pending)
        assertEquals(CommandConsent.Given("cache FLUSHALL"), service.consents.last())
    }

    @Test
    fun `a refusal is a transcript entry rather than a question`() = runTest {
        val service = service()
        service.nextFailure = DbException(
            DbError.CommandNotAllowed(
                message = "This connection is read only, and DEL is not one of the commands " +
                    "known to only read.",
                command = "DEL",
                reason = DbError.CommandNotAllowed.Reason.READ_ONLY,
            ),
        )
        val model = model(service)
        model.edit("DEL user:42")

        model.run()
        advanceUntilIdle()

        // No override is offered, because on a read-only connection none exists.
        assertNull(model.pending)
        assertEquals("command_not_allowed_read_only", model.entries.single().failure?.code)
    }

    @Test
    fun `the arrows walk the lines typed this session`() = runTest {
        val service = service()
        val model = model(service)
        listOf("PING", "GET a").forEach { line ->
            model.edit(line)
            model.run()
            advanceUntilIdle()
        }

        model.recallEarlier()
        assertEquals("GET a", model.line)
        model.recallEarlier()
        assertEquals("PING", model.line)
        model.recallLater()
        assertEquals("GET a", model.line)
        model.recallLater()
        // Walking forward past the newest returns to where the user was, which is an
        // empty line and not the oldest entry.
        assertEquals("", model.line)
    }

    @Test
    fun `locking takes the transcript and the recalled lines with it`() = runTest {
        val service = service()
        val model = model(service)
        model.edit("AUTH hunter2")
        model.run()
        advanceUntilIdle()

        model.clear()

        assertTrue(model.entries.isEmpty())
        model.recallEarlier()
        // Nothing to recall: a command's arguments are where its secrets are, and this
        // is the only place they were ever held.
        assertEquals("", model.line)
    }
}
