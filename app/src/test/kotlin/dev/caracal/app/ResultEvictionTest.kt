package dev.caracal.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.ColumnFormat
import dev.caracal.core.result.QueryResult
import dev.caracal.core.vault.VaultState
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * §4.10's memory rule: a bounded number of result models, and nothing lost by it.
 *
 * The number of tabs is the only axis on which this application's memory grows
 * without a limit. One result is capped by `ResultLimits` at a thousand rows and
 * sixteen megabytes of characters; twenty tabs is twenty of those, and twenty tabs is
 * a figure §4.10 names as ordinary rather than as abuse.
 *
 * The half worth defending hardest is not the eviction — it is that eviction is
 * invisible in consequence. The script stays, the statement is still named, and
 * running it again is one button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultEvictionTest {

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        queryResult = QueryResult(
            columns = listOf(Column("n", "int8", ColumnFormat.NUMBER)),
            rows = listOf(listOf(CellValue.Integer(1))),
            duration = 3.milliseconds,
        )
    }

    private fun connection(id: String = "id-1") = networkConfig(
        id = ConnectionId(id),
        name = "local",
        engineId = POSTGRES,
        host = "localhost",
        port = 5432,
        database = "caracal",
        username = "caracal",
        tlsMode = TlsMode.DISABLE,
        environment = Environment.DEV,
        readOnly = false,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    /**
     * [count] tabs, each having run one statement, in the order they were opened.
     *
     * The last one opened is the most recently activated, which is what the eviction
     * order is read from.
     */
    private fun TestScope.workedIn(count: Int): Pair<EditorTabs, List<EditorTab>> {
        val tabs = EditorTabs(service(), this) { null }
        val config = connection()
        val opened = (1..count).map { n ->
            tabs.open(config).also { tab ->
                tab.editor.edit(TextFieldValue("select $n", TextRange(8)))
                tab.editor.execute()
                advanceUntilIdle()
            }
        }
        return tabs to opened
    }

    @Test
    fun `a handful of tabs all keep their results`() = runTest {
        val (_, opened) = workedIn(6)

        assertTrue(opened.all { it.editor.run is EditorRun.Done })
    }

    @Test
    fun `past the cap, the tabs nobody has been in give up their grids`() = runTest {
        val (tabs, opened) = workedIn(20)
        advanceUntilIdle()

        val kept = opened.filter { it.editor.run is EditorRun.Done }
        val released = opened.filter { it.editor.run is EditorRun.Released }

        assertEquals(20, tabs.tabs.size, "no tab was closed to save memory")
        assertEquals(8, kept.size)
        assertEquals(12, released.size)
        // The most recent eight, not the first eight: what is kept is what someone is
        // working in.
        assertEquals(opened.takeLast(8), kept)
    }

    @Test
    fun `an evicted tab keeps its script and can say what produced the result`() = runTest {
        val (_, opened) = workedIn(20)
        val evicted = opened.first()

        val state = assertIs<EditorRun.Released>(evicted.editor.run)
        assertEquals("select 1", state.target.sql)
        // The script itself was never touched. This is the difference between letting
        // a result go and losing someone's work.
        assertEquals("select 1", evicted.editor.text.text)
    }

    @Test
    fun `coming back to an evicted tab and running it again brings the result back`() = runTest {
        val (tabs, opened) = workedIn(20)
        val evicted = opened.first()
        assertIs<EditorRun.Released>(evicted.editor.run)

        tabs.activate(evicted)
        evicted.editor.execute()
        advanceUntilIdle()

        assertIs<EditorRun.Done>(evicted.editor.run)
    }

    /**
     * Returning to an old tab must not cost the result of the tab in front of it if
     * the window has room for both — and when it does not, the one given up is the
     * one that has now been idle longest.
     */
    @Test
    fun `activating an old tab evicts the next-oldest, not the one just used`() = runTest {
        val (tabs, opened) = workedIn(9)
        val oldest = opened.first()
        val secondOldest = opened[1]

        assertIs<EditorRun.Released>(oldest.editor.run)
        assertIs<EditorRun.Done>(secondOldest.editor.run)

        tabs.activate(oldest)
        oldest.editor.execute()
        advanceUntilIdle()

        assertIs<EditorRun.Done>(oldest.editor.run)
        assertIs<EditorRun.Released>(secondOldest.editor.run)
    }

    /**
     * The rule that makes automatic eviction defensible: nothing in flight is touched.
     *
     * An export is rows streaming to a file the user named. Cancelling one because
     * its tab had gone quiet would delete a half-written file — a loss, where every
     * other eviction costs a keypress.
     */
    @Test
    fun `a tab with an export running keeps its result and its file`() = runTest {
        val service = service()
        val tabs = EditorTabs(service, this) { java.nio.file.Path.of("/tmp/out.csv") }
        val config = connection()

        val exporting = tabs.open(config)
        exporting.editor.edit(TextFieldValue("select 1", TextRange(8)))
        exporting.editor.execute()
        advanceUntilIdle()
        assertIs<EditorRun.Done>(exporting.editor.run)

        service.gate = CompletableDeferred()
        exporting.export.start(config.id, "select 1", config.name)
        advanceUntilIdle()
        assertTrue(exporting.export.running)

        repeat(20) { tabs.open(config) }
        advanceUntilIdle()

        assertIs<EditorRun.Done>(exporting.editor.run)
        assertTrue(exporting.export.running, "the export was cancelled to save memory")

        exporting.export.cancel()
        advanceUntilIdle()
    }

    /** A statement on the server is not a result, and is not what eviction is for. */
    @Test
    fun `a tab with a statement running is left alone`() = runTest {
        val service = service()
        val tabs = EditorTabs(service, this) { null }
        val config = connection()

        val waiting = tabs.open(config)
        waiting.editor.edit(TextFieldValue("select pg_sleep(60)", TextRange(19)))
        service.gate = CompletableDeferred()
        waiting.editor.execute()
        advanceUntilIdle()
        assertIs<EditorRun.Running>(waiting.editor.run)

        // Twenty more tabs, all more recently used than the one still waiting.
        repeat(20) { tabs.open(config) }
        advanceUntilIdle()

        assertIs<EditorRun.Running>(waiting.editor.run)

        // Let the statement go, so the test's scope is not left holding it.
        waiting.editor.cancel()
        advanceUntilIdle()
    }
}
