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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * §4.3, without a window.
 *
 * What is asserted here is the half of tabs that is not drawing: that a tab keeps the
 * connection it was opened on when another one is selected, that a query and its
 * result stay with the tab that asked for them while the user is somewhere else, and
 * that no tab holding a script or a running statement can leave without saying what
 * it would cost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorTabsTest {

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        queryResult = QueryResult(
            columns = listOf(Column("n", "int8", ColumnFormat.NUMBER)),
            rows = listOf(listOf(CellValue.Integer(1))),
            duration = 3.milliseconds,
        )
    }

    private fun TestScope.tabs(service: FakeConnectionService = service()) =
        EditorTabs(service, this) { null }

    private fun connection(
        id: String = "id-1",
        name: String = "local",
        readOnly: Boolean = false,
    ) = networkConfig(
        id = ConnectionId(id),
        name = name,
        engineId = POSTGRES,
        host = "localhost",
        port = 5432,
        database = "caracal",
        username = "caracal",
        tlsMode = TlsMode.DISABLE,
        environment = Environment.DEV,
        readOnly = readOnly,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    private fun EditorTab.type(sql: String) {
        editor.edit(TextFieldValue(sql, TextRange(sql.length)))
    }

    // --- Opening --------------------------------------------------------------

    @Test
    fun `a connection shown for the first time gets one tab`() = runTest {
        val tabs = tabs()
        val local = connection()

        tabs.show(local)

        assertEquals(1, tabs.tabs.size)
        assertEquals(local, tabs.active(local.id)?.connection)
    }

    @Test
    fun `closing the last tab is a decision, and reselecting does not undo it`() = runTest {
        val tabs = tabs()
        val local = connection()
        tabs.show(local)

        tabs.requestClose(tabs.tabs.single())
        assertEquals(emptyList(), tabs.tabs)

        // Shown again — selecting the connection, or coming back from another one.
        // The empty state is what the user asked for by closing the tab.
        tabs.show(local)
        assertEquals(emptyList(), tabs.tabs)
    }

    @Test
    fun `an edited connection reaches the tabs already open on it`() = runTest {
        val tabs = tabs()
        tabs.show(connection(readOnly = false))
        val tab = tabs.tabs.single()

        tabs.show(connection(readOnly = true))

        assertEquals(true, tab.connection?.readOnly)
        assertEquals(1, tabs.tabs.size)
    }

    @Test
    fun `a statement reopened into a new tab is not unsaved work`() = runTest {
        val tabs = tabs()
        val local = connection()

        val tab = tabs.open(local, "select now();")

        assertEquals("select now();", tab.editor.text.text)
        // It is exactly what history has, so closing it asks nothing.
        assertFalse(tab.dirty)
        tabs.requestClose(tab)
        assertNull(tabs.closing)
        assertEquals(emptyList(), tabs.tabs)
    }

    // --- Tabs keep their own connection ---------------------------------------

    @Test
    fun `selecting another connection does not retarget the tab that was open`() = runTest {
        val tabs = tabs()
        val local = connection()
        val other = connection(id = "id-2", name = "staging")
        tabs.show(local)
        val first = tabs.tabs.single()
        first.type("select 1;")

        tabs.show(other)

        assertEquals(local.id, first.connectionId)
        assertEquals("select 1;", first.editor.text.text)
        // The second connection has a strip of its own, and the first tab is not in it.
        assertEquals(listOf(first), tabs.of(local.id))
        assertEquals(1, tabs.of(other.id).size)
        assertTrue(first !in tabs.of(other.id))
    }

    @Test
    fun `each tab keeps its own result while the other one is being worked in`() = runTest {
        val service = service()
        val tabs = tabs(service)
        val local = connection()
        tabs.show(local)
        val first = tabs.tabs.single()
        first.type("select 1;")
        first.editor.execute()
        advanceUntilIdle()

        val second = tabs.open(local)
        second.type("select 2;")
        second.editor.execute()
        advanceUntilIdle()

        assertIs<EditorRun.Done>(first.run())
        assertEquals("select 1;", (first.run() as EditorRun.Done).target.sql)
        assertEquals("select 2;", (second.run() as EditorRun.Done).target.sql)
    }

    @Test
    fun `a query finishes in the tab that sent it, whichever one is on screen`() = runTest {
        val service = service()
        service.gate = CompletableDeferred()
        val tabs = tabs(service)
        val local = connection()
        tabs.show(local)
        val running = tabs.tabs.single()
        running.type("select pg_sleep(30);")
        running.editor.execute()

        // The user moves on. The statement is still on the server, and it belongs to
        // the tab that sent it rather than to whichever tab is in front.
        val other = tabs.open(local)
        assertSame(other, tabs.active(local.id))
        assertTrue(running.running)

        service.gate?.complete(Unit)
        advanceUntilIdle()

        assertIs<EditorRun.Done>(running.run())
        assertIs<EditorRun.Idle>(other.run())
    }

    // --- Closing --------------------------------------------------------------

    @Test
    fun `a tab holding a script asks before it closes`() = runTest {
        val tabs = tabs()
        tabs.show(connection())
        val tab = tabs.tabs.single()
        tab.type("select 1;")

        tabs.requestClose(tab)

        assertEquals(CloseReason.DIRTY, tabs.closing?.reason)
        assertEquals(listOf(tab), tabs.tabs)

        tabs.cancelClose()
        assertNull(tabs.closing)
        assertEquals(listOf(tab), tabs.tabs)

        tabs.requestClose(tab)
        tabs.confirmClose()
        assertEquals(emptyList(), tabs.tabs)
    }

    @Test
    fun `a tab with a statement on the server offers to cancel it rather than orphan it`() =
        runTest {
            val service = service()
            service.gate = CompletableDeferred()
            val tabs = tabs(service)
            tabs.show(connection())
            val tab = tabs.tabs.single()
            tab.type("select pg_sleep(30);")
            tab.editor.execute()

            tabs.requestClose(tab)

            val closing = assertNotNull(tabs.closing)
            assertEquals(CloseReason.RUNNING, closing.reason)
            // Both facts, because both are true and only one of them is in the title.
            assertTrue(closing.losesScript)

            tabs.confirmClose()
            advanceUntilIdle()

            assertEquals(emptyList(), tabs.tabs)
            assertFalse(tab.running)
            service.gate?.complete(Unit)
        }

    @Test
    fun `a blank tab closes without a question`() = runTest {
        val tabs = tabs()
        tabs.show(connection())
        val tab = tabs.tabs.single()
        tab.type("   \n  ")

        tabs.requestClose(tab)

        assertNull(tabs.closing)
        assertEquals(emptyList(), tabs.tabs)
    }

    @Test
    fun `the tab that takes over is the one last worked in`() = runTest {
        val tabs = tabs()
        val local = connection()
        tabs.show(local)
        val first = tabs.tabs.single()
        val second = tabs.open(local)
        val third = tabs.open(local)
        tabs.activate(first)
        tabs.activate(second)

        assertSame(second, tabs.active(local.id))

        tabs.requestClose(second)

        assertSame(first, tabs.active(local.id))
        assertEquals(listOf(first, third), tabs.tabs)
    }

    // --- Duplicating and moving -----------------------------------------------

    @Test
    fun `duplicating copies the script and the connection, and not the query`() = runTest {
        val service = service()
        service.gate = CompletableDeferred()
        val tabs = tabs(service)
        val local = connection()
        tabs.show(local)
        val original = tabs.tabs.single()
        original.type("select pg_sleep(30);")
        original.editor.execute()

        val copy = assertNotNull(tabs.duplicate(original))

        assertEquals("select pg_sleep(30);", copy.editor.text.text)
        assertEquals(local.id, copy.connectionId)
        assertFalse(copy.running)
        assertTrue(original.running)
        // A second copy of a script that is saved nowhere is a second thing to lose.
        assertTrue(copy.dirty)
        service.gate?.complete(Unit)
    }

    @Test
    fun `moving a tab takes the script and drops the result`() = runTest {
        val service = service()
        val tabs = tabs(service)
        val local = connection()
        val staging = connection(id = "id-2", name = "staging")
        tabs.show(local)
        val tab = tabs.tabs.single()
        tab.type("select 1;")
        tab.editor.execute()
        advanceUntilIdle()
        assertIs<EditorRun.Done>(tab.run())

        tabs.retarget(tab, staging)

        assertEquals(staging.id, tab.connectionId)
        assertEquals("select 1;", tab.editor.text.text)
        // A grid is a claim about one server, and this is no longer that server.
        assertIs<EditorRun.Idle>(tab.run())
        assertEquals(listOf(tab), tabs.of(staging.id))
        assertEquals(emptyList(), tabs.of(local.id))
    }

    @Test
    fun `a tab with a statement running cannot be moved out from under it`() = runTest {
        val service = service()
        service.gate = CompletableDeferred()
        val tabs = tabs(service)
        val local = connection()
        tabs.show(local)
        val tab = tabs.tabs.single()
        tab.type("select pg_sleep(30);")
        tab.editor.execute()

        tabs.retarget(tab, connection(id = "id-2", name = "staging"))

        assertEquals(local.id, tab.connectionId)
        assertTrue(tab.running)
        service.gate?.complete(Unit)
    }

    // --- Losing everything at once --------------------------------------------

    @Test
    fun `deleting a connection takes its tabs and leaves the others`() = runTest {
        val tabs = tabs()
        val local = connection()
        val staging = connection(id = "id-2", name = "staging")
        tabs.show(local)
        tabs.show(staging)
        val kept = tabs.active(staging.id)

        tabs.forget(local.id)

        assertEquals(listOfNotNull(kept), tabs.tabs)
    }

    @Test
    fun `locking drops every tab and stops what they were running`() = runTest {
        val service = service()
        service.gate = CompletableDeferred()
        val tabs = tabs(service)
        val local = connection()
        tabs.show(local)
        val tab = tabs.tabs.single()
        tab.type("select pg_sleep(30);")
        tab.editor.execute()
        assertTrue(tabs.dirty)

        tabs.clear()
        advanceUntilIdle()

        assertEquals(emptyList(), tabs.tabs)
        assertFalse(tabs.dirty)
        assertFalse(tab.running)
        service.gate?.complete(Unit)
    }

    @Test
    fun `the window has something to lose only while a tab holds a script`() = runTest {
        val tabs = tabs()
        val local = connection()
        tabs.show(local)
        assertFalse(tabs.dirty)

        tabs.tabs.single().type("select 1;")

        assertTrue(tabs.dirty)
    }

    private fun EditorTab.run(): EditorRun = editor.run
}

/** What the strip calls a tab, given what has been typed into it. */
class TabTitleTest {

    @Test
    fun `an empty tab is untitled`() {
        assertEquals(TabTitle.UNTITLED, TabTitle.of(""))
        assertEquals(TabTitle.UNTITLED, TabTitle.of("   \n\n  "))
    }

    @Test
    fun `a tab is named by its first line, without its semicolon`() {
        assertEquals("select 1", TabTitle.of("select 1;"))
        assertEquals("select 1", TabTitle.of("\n\n  select 1;\nselect 2;"))
        assertEquals("-- invoices", TabTitle.of("-- invoices\nselect * from invoices;"))
    }

    @Test
    fun `a long first line is cut rather than allowed to size the tab`() {
        val title = TabTitle.of("select id, total, created_at from invoices where paid is false;")
        assertTrue(title.length <= 26, title)
        assertTrue(title.endsWith("…"), title)
    }

    @Test
    fun `the run of whitespace a formatter leaves behind is collapsed`() {
        assertEquals("select a", TabTitle.of("select \t  a"))
    }
}
