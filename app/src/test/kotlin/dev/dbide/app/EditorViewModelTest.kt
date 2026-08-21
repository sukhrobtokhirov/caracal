package dev.dbide.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.policy.Acknowledgement
import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import dev.dbide.core.result.ColumnFormat
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import dev.dbide.core.result.ErrorSubject
import dev.dbide.core.result.QueryResult
import dev.dbide.core.sql.Execution
import dev.dbide.core.sql.StatementKind
import dev.dbide.core.sql.TargetSource
import dev.dbide.core.vault.VaultState
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What the editor sends, and what it does with the answer.
 *
 * The rule about *which* statement is chosen belongs to `:core` and is tested there.
 * What is asserted here is the part that only exists once a person is holding the
 * thing: that Run sends the statement the toolbar said it would, that a second Run
 * while one is in flight does nothing, that cancelling stops the query and nothing
 * else, and that a result which arrives after a cancellation is not quietly shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private fun service() = FakeConnectionService(VaultState.UNLOCKED)

    private fun TestScope.editor(
        service: FakeConnectionService = service(),
        connection: ConnectionConfig = connection(),
    ): EditorViewModel = EditorViewModel(service, this).also { it.show(connection) }

    /**
     * A connection for the editor to point at.
     *
     * Writable and non-production by default, because that is the connection every
     * test that is not about §2.4 wants: one where pressing Run sends the statement
     * rather than opening a dialog.
     */
    private fun connection(
        id: String = "id-1",
        name: String = "local",
        environment: Environment = Environment.DEV,
        readOnly: Boolean = false,
    ) = ConnectionConfig(
        id = ConnectionId(id),
        name = name,
        engine = Engine.POSTGRES,
        host = "localhost",
        port = 5432,
        database = "dbide",
        username = "dbide",
        tlsMode = TlsMode.DISABLE,
        environment = environment,
        readOnly = readOnly,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    /** Types [script] and puts the caret where `|` is, or at the end if there is none. */
    private fun EditorViewModel.type(script: String) {
        val caret = script.indexOf('|')
        val text = script.replace("|", "")
        edit(TextFieldValue(text, TextRange(if (caret < 0) text.length else caret)))
    }

    private fun EditorViewModel.select(script: String, from: Int, to: Int) {
        edit(TextFieldValue(script, TextRange(from, to)))
    }

    // --- What gets sent -------------------------------------------------------

    @Test
    fun `Run sends the statement the caret is in`() = runTest {
        val service = service()
        val editor = editor(service)
        editor.type("select 1;\nselect |2;\nselect 3;")

        editor.execute()
        advanceUntilIdle()

        assertEquals(listOf("select 2;"), service.executed)
    }

    @Test
    fun `Run sends the selection when there is one`() = runTest {
        val service = service()
        val editor = editor(service)
        val script = "select 1; select 2;"
        editor.select(script, 10, 19)

        editor.execute()
        advanceUntilIdle()

        assertEquals(listOf("select 2;"), service.executed)
        assertEquals(TargetSource.SELECTION, editor.target?.source)
    }

    @Test
    fun `Run is unavailable when the selection is not one statement`() = runTest {
        val service = service()
        val editor = editor(service)
        val script = "select 1; select 2;"
        editor.select(script, 0, script.length)

        assertFalse(editor.runnable)
        editor.execute()
        advanceUntilIdle()

        assertTrue(service.executed.isEmpty())
        assertIs<Execution.Refused>(editor.execution)
    }

    @Test
    fun `Run is unavailable with no connection to send to`() = runTest {
        val service = service()
        val editor = EditorViewModel(service, this)
        editor.type("select 1;")

        assertFalse(editor.runnable)
        editor.execute()
        advanceUntilIdle()

        assertTrue(service.executed.isEmpty())
    }

    @Test
    fun `the toolbar names the statement Run would send`() = runTest {
        val editor = editor()

        editor.type("select 1;\nselect |2;\nselect 3;")
        assertEquals("Runs statement 2 of 3.", editor.runLabel)

        editor.select("select 1; select 2;", 10, 19)
        assertEquals("Runs the selection.", editor.runLabel)

        editor.type("select 'oops|")
        assertEquals("A quoted string is never closed.", editor.runLabel)
    }

    // --- One at a time --------------------------------------------------------

    @Test
    fun `a second Run while one is in flight is ignored`() = runTest {
        val service = service()
        service.gate = CompletableDeferred()
        val editor = editor(service)
        editor.type("select 1;")

        editor.execute()
        advanceUntilIdle()
        assertTrue(editor.running)
        assertFalse(editor.runnable)

        editor.execute()
        service.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("select 1;"), service.executed)
    }

    @Test
    fun `a result becomes a grid`() = runTest {
        val service = service()
        service.queryResult = QueryResult(
            columns = listOf(Column("total", "numeric", ColumnFormat.NUMBER)),
            rows = listOf(listOf(CellValue.Text("1.00"))),
            duration = 7.milliseconds,
        )
        val editor = editor(service)
        editor.type("select total from invoices;")

        editor.execute()
        advanceUntilIdle()

        val done = assertIs<EditorRun.Done>(editor.run)
        assertEquals("total", done.grid.result.columns.single().name)
    }

    @Test
    fun `a failed statement is classified rather than thrown`() = runTest {
        val service = service()
        service.nextFailure = DbException(DbError.QueryFailed("relation \"nope\" does not exist"))
        val editor = editor(service)
        editor.type("select * from nope;")

        editor.execute()
        advanceUntilIdle()

        val failed = assertIs<EditorRun.Failed>(editor.run)
        assertEquals("query_failed", failed.failure.code)
    }

    // --- Cancellation ---------------------------------------------------------

    @Test
    fun `cancelling stops the query and leaves the script alone`() = runTest {
        val service = service()
        service.gate = CompletableDeferred()
        val editor = editor(service)
        editor.type("select pg_sleep(30);")

        editor.execute()
        advanceUntilIdle()
        editor.cancel()

        assertEquals(EditorRun.Cancelled, editor.run)
        assertEquals("select pg_sleep(30);", editor.text.text)
        // The editor is usable again immediately; nothing waits for the server.
        assertTrue(editor.runnable)

        // The gated call completes afterwards, and its result is not shown: the user
        // asked for it to stop.
        service.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(EditorRun.Cancelled, editor.run)
    }

    @Test
    fun `cancelling when nothing is running does nothing`() = runTest {
        val editor = editor()
        editor.type("select 1;")

        editor.cancel()

        assertEquals(EditorRun.Idle, editor.run)
    }

    // --- §2.4: the safety gate ------------------------------------------------

    @Test
    fun `a read on a writable connection is sent without asking`() = runTest {
        val service = service()
        val editor = editor(service)
        editor.type("select * from invoices;")

        editor.execute()
        advanceUntilIdle()

        assertNull(editor.pending)
        assertEquals(listOf("select * from invoices;"), service.executed)
    }

    @Test
    fun `a write on a writable connection is held until it is confirmed`() = runTest {
        val service = service()
        val editor = editor(service)
        editor.type("delete from invoices;")

        editor.execute()
        advanceUntilIdle()

        // Held, not sent. This is the assertion the whole section is for.
        assertEquals(emptyList(), service.executed)
        val pending = assertNotNull(editor.pending)
        assertEquals(StatementKind.WRITE, pending.clearance.kind)
        assertEquals(Acknowledgement.CLICK, pending.clearance.acknowledgement)
        assertEquals(EditorRun.Idle, editor.run)
    }

    @Test
    fun `confirming sends the statement the dialog described`() = runTest {
        val service = service()
        val editor = editor(service)
        editor.type("delete from invoices;")
        editor.execute()

        editor.confirm()
        advanceUntilIdle()

        assertNull(editor.pending)
        assertEquals(listOf("delete from invoices;"), service.executed)
        assertIs<EditorRun.Done>(editor.run)
    }

    @Test
    fun `confirming sends what was asked about, not whatever the caret has since found`() = runTest {
        // The reason the target is captured when the dialog opens. Between reading a
        // confirmation and clicking its button the caret can move — and what runs must
        // be the statement that was described, or the dialog was a lie.
        val service = service()
        val editor = editor(service)
        editor.type("delete from invoices;\nselect 1;|")
        editor.edit(TextFieldValue(editor.text.text, TextRange(6)))
        editor.execute()
        assertNotNull(editor.pending)

        // The caret moves into the second statement while the dialog is open.
        editor.edit(TextFieldValue(editor.text.text, TextRange(editor.text.text.length)))
        editor.confirm()
        advanceUntilIdle()

        assertEquals(listOf("delete from invoices;"), service.executed)
    }

    @Test
    fun `cancelling a confirmation runs nothing and leaves the editor as it was`() = runTest {
        val service = service()
        val editor = editor(service)
        editor.type("drop table invoices;")
        editor.execute()

        editor.cancelConfirmation()
        advanceUntilIdle()

        assertNull(editor.pending)
        assertEquals(emptyList(), service.executed)
        assertEquals(EditorRun.Idle, editor.run)
        assertEquals("drop table invoices;", editor.text.text)
        assertTrue(editor.runnable, "Run was left unavailable after a confirmation was dismissed")
    }

    @Test
    fun `a production write is not released by an empty or wrong acknowledgement`() = runTest {
        val service = service()
        val editor = editor(service, connection(name = "payments-prod", environment = Environment.PROD))
        editor.type("update invoices set total = 0;")
        editor.execute()

        val pending = assertNotNull(editor.pending)
        assertEquals(Acknowledgement.TYPED, pending.clearance.acknowledgement)

        editor.confirm()
        editor.confirm("payments")
        editor.confirm("PAYMENTS-PROD")
        advanceUntilIdle()

        // Still waiting, and nothing has been sent. The check lives here and not only
        // in the dialog's disabled button: this is the one place a production write
        // can be released.
        assertNotNull(editor.pending)
        assertEquals(emptyList(), service.executed)
    }

    @Test
    fun `a production write is released by the connection's name`() = runTest {
        val service = service()
        val editor = editor(service, connection(name = "payments-prod", environment = Environment.PROD))
        editor.type("update invoices set total = 0;")
        editor.execute()

        editor.confirm("  payments-prod ")
        advanceUntilIdle()

        assertEquals(listOf("update invoices set total = 0;"), service.executed)
    }

    @Test
    fun `a write on a read-only connection is refused without a round trip`() = runTest {
        val service = service()
        val editor = editor(service, connection(readOnly = true))
        editor.type("delete from invoices;")

        editor.execute()
        advanceUntilIdle()

        assertNull(editor.pending, "a read-only connection asked instead of refusing")
        assertEquals(emptyList(), service.executed)
        val failed = assertIs<EditorRun.Failed>(editor.run)
        assertEquals("read_only_connection", failed.failure.code)
    }

    @Test
    fun `a read on a read-only connection still runs`() = runTest {
        val service = service()
        val editor = editor(service, connection(readOnly = true))
        editor.type("select * from invoices;")

        editor.execute()
        advanceUntilIdle()

        assertEquals(listOf("select * from invoices;"), service.executed)
    }

    @Test
    fun `Run does nothing while a confirmation is open`() = runTest {
        val service = service()
        val editor = editor(service)
        editor.type("delete from invoices;")
        editor.execute()
        val first = assertNotNull(editor.pending)

        editor.execute()

        assertFalse(editor.runnable)
        assertEquals(first, editor.pending, "a second Run replaced the confirmation already open")
        assertEquals(emptyList(), service.executed)
    }

    @Test
    fun `a confirmation does not survive a change of connection`() = runTest {
        // A dialog raised against one server must not be answerable against another.
        val service = service()
        val editor = editor(service)
        editor.type("delete from invoices;")
        editor.execute()
        assertNotNull(editor.pending)

        editor.show(connection(id = "id-2", name = "other"))

        assertNull(editor.pending)
        editor.confirm()
        advanceUntilIdle()
        assertEquals(emptyList(), service.executed)
    }

    @Test
    fun `unticking read only reaches the policy without the editor being reopened`() = runTest {
        val service = service()
        val editor = editor(service, connection(readOnly = true))
        editor.type("delete from invoices;")
        editor.execute()
        assertIs<EditorRun.Failed>(editor.run)

        editor.show(connection(readOnly = false))
        editor.execute()

        assertNotNull(editor.pending, "the editor was still holding the old read-only answer")
    }

    // --- §2.9: where the server said the problem was --------------------------

    @Test
    fun `a server position becomes a place in the document`() = runTest {
        val service = service()
        service.nextFailure = DbException(
            DbError.QueryFailed("column \"totl\" does not exist", sqlState = "42703", position = 8),
        )
        val editor = editor(service)
        // Two statements, so the offset is only right if the statement's own start is
        // added to it. A position counted from the wrong origin passes any test with
        // one statement in it.
        editor.type("select 1;\nselect totl from invoices;|")

        editor.execute()
        advanceUntilIdle()

        val failed = assertIs<EditorRun.Failed>(editor.run)
        assertEquals(editor.text.text.indexOf("totl"), failed.errorAt)
    }

    @Test
    fun `a server position counts characters, not UTF-16 units`() = runTest {
        // PostgreSQL counts code points and Kotlin counts UTF-16 units. One character
        // outside the BMP before the error and every highlight after it is off by one.
        val sql = "select '🧾 receipt' as note, totl from invoices;"
        val service = service()
        service.nextFailure = DbException(
            DbError.QueryFailed(
                message = "column \"totl\" does not exist",
                // What PostgreSQL would report: 1-based, counted in characters. It is
                // computed rather than written out so that the assertion below is
                // about the conversion and not about my arithmetic.
                position = sql.codePointCount(0, sql.indexOf("totl")) + 1,
            ),
        )
        val editor = editor(service)
        editor.type("$sql|")

        editor.execute()
        advanceUntilIdle()

        val failed = assertIs<EditorRun.Failed>(editor.run)
        assertEquals(editor.text.text.indexOf("totl"), failed.errorAt)
    }

    @Test
    fun `a failure with no position points at nothing`() = runTest {
        val service = service()
        service.nextFailure = DbException(DbError.QueryFailed("the connection died"))
        val editor = editor(service)
        editor.type("select 1;")

        editor.execute()
        advanceUntilIdle()

        val failed = assertIs<EditorRun.Failed>(editor.run)
        assertNull(failed.errorAt, "a position was guessed at where the server gave none")
    }

    @Test
    fun `the marker stays while the script around the failed statement is edited`() = runTest {
        val service = service()
        service.nextFailure = DbException(
            DbError.QueryFailed("column \"totl\" does not exist", position = 8),
        )
        val editor = editor(service)
        editor.type("select totl from invoices;|")

        editor.execute()
        advanceUntilIdle()

        val marked = assertIs<ErrorMarker.At>(editor.marker)
        assertEquals(editor.text.text.indexOf("totl"), marked.index)

        // Writing the next statement below is not a reason to take the error away —
        // it is frequently what the user does while still reading it.
        editor.type("select totl from invoices;\nselect 2;|")
        assertEquals(marked, editor.marker)
    }

    @Test
    fun `the marker goes when the text it counted into changes`() = runTest {
        val service = service()
        service.nextFailure = DbException(
            DbError.QueryFailed("column \"totl\" does not exist", position = 8),
        )
        val editor = editor(service)
        editor.type("select totl from invoices;|")

        editor.execute()
        advanceUntilIdle()
        assertIs<ErrorMarker.At>(editor.marker)

        // The fix. Offset 7 is now the `f` of `from` in a longer script, and drawing
        // an underline there would be the editor confidently pointing at the wrong
        // word — §4.6's rule is that it says the position is gone instead.
        editor.type("select total from invoices;|")

        assertIs<ErrorMarker.Moved>(editor.marker)
    }

    @Test
    fun `an insertion above the failed statement moves the marker rather than sliding it`() =
        runTest {
            val service = service()
            service.nextFailure = DbException(
                DbError.QueryFailed("column \"totl\" does not exist", position = 8),
            )
            val editor = editor(service)
            editor.type("select totl from invoices;|")

            editor.execute()
            advanceUntilIdle()

            // The statement is character-for-character what was sent, four characters
            // to the right. Its old offsets now describe the comment.
            editor.type("-- x\nselect totl from invoices;|")

            assertIs<ErrorMarker.Moved>(editor.marker)
        }

    @Test
    fun `a failure raised before a statement was chosen points at nothing`() = runTest {
        val editor = editor(connection = connection(readOnly = true))
        editor.type("delete from invoices;|")

        editor.execute()

        assertIs<EditorRun.Failed>(editor.run)
        // Refused by the policy, so nothing was sent and no server named a character.
        assertIs<ErrorMarker.None>(editor.marker)
    }

    @Test
    fun `running again clears the marker before the next answer arrives`() = runTest {
        val service = service()
        service.nextFailure = DbException(
            DbError.QueryFailed("column \"totl\" does not exist", position = 8),
        )
        val editor = editor(service)
        editor.type("select totl from invoices;|")
        editor.execute()
        advanceUntilIdle()
        assertIs<ErrorMarker.At>(editor.marker)

        service.gate = CompletableDeferred()
        editor.execute()

        assertIs<ErrorMarker.None>(editor.marker)

        service.gate?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `the structured server report reaches the editor intact`() = runTest {
        // §2.9's point: a server error is not one sentence, and flattening it into one
        // on the way to the UI throws away the hint that is often the whole answer.
        val service = service()
        service.nextFailure = DbException(
            DbError.QueryFailed(
                message = "column \"totl\" does not exist",
                sqlState = "42703",
                position = 8,
                hint = "Perhaps you meant \"total\".",
                severity = "ERROR",
                subject = ErrorSubject(schema = "sales", table = "invoices"),
            ),
        )
        val editor = editor(service)
        editor.type("select totl from invoices;")

        editor.execute()
        advanceUntilIdle()

        val query = assertNotNull(assertIs<EditorRun.Failed>(editor.run).failure.query)
        assertEquals("42703", query.sqlState)
        assertEquals("Perhaps you meant \"total\".", query.hint)
        assertEquals("sales.invoices", query.subject?.describe())
    }

    @Test
    fun `a cancellation is not a failure and points at nothing`() = runTest {
        val service = service()
        service.gate = CompletableDeferred()
        val editor = editor(service)
        editor.type("select pg_sleep(30);")
        editor.execute()

        editor.cancel()
        advanceUntilIdle()

        assertEquals(EditorRun.Cancelled, editor.run)
    }

    // --- The connection it belongs to ----------------------------------------

    @Test
    fun `switching connection keeps the script and drops the result`() = runTest {
        val service = service()
        val editor = editor(service)
        editor.type("select 1;")
        editor.execute()
        advanceUntilIdle()
        assertIs<EditorRun.Done>(editor.run)

        editor.show(connection(id = "id-2"))

        assertEquals("select 1;", editor.text.text)
        assertEquals(EditorRun.Idle, editor.run)
    }

    @Test
    fun `locking clears the script, the result, and the connection`() = runTest {
        val editor = editor()
        editor.type("select 1;")
        editor.execute()
        advanceUntilIdle()

        editor.clear()

        assertEquals("", editor.text.text)
        assertEquals(EditorRun.Idle, editor.run)
        assertFalse(editor.runnable)
    }

    // --- Reopening a statement from history -----------------------------------

    @Test
    fun `a reopened statement goes straight into an empty editor, with the caret after it`() = runTest {
        val editor = editor()
        editor.type("   \n  |")

        editor.open("select now()")

        // Whitespace is not work. Asking whether to replace three spaces and a
        // newline is a question with only one sensible answer, asked every time.
        assertNull(editor.pendingScript)
        assertEquals("select now()", editor.text.text)
        assertEquals("select now()".length, editor.text.selection.start)
    }

    @Test
    fun `a reopened statement asks before it lands on top of a script`() = runTest {
        val editor = editor()
        editor.type("delete from invoices where id = 7|")

        editor.open("select now()")

        assertEquals("select now()", editor.pendingScript)
        // Nothing has happened to the editor yet, and Run would still send what is
        // there — the question is open, not answered.
        assertEquals("delete from invoices where id = 7", editor.text.text)
    }

    @Test
    fun `agreeing takes the reopened statement and declining keeps the script`() = runTest {
        val editor = editor()
        editor.type("select 1|")
        editor.open("select 2")
        editor.cancelOpen()

        assertNull(editor.pendingScript)
        assertEquals("select 1", editor.text.text)

        editor.open("select 2")
        editor.confirmOpen()

        assertNull(editor.pendingScript)
        assertEquals("select 2", editor.text.text)
        // The script that arrived is the one Run would now send, which means the
        // splitter has seen it — a replacement that only set the text would leave the
        // toolbar describing the statement that is no longer there.
        assertIs<Execution.Ready>(editor.execution)
        assertEquals("select 2", (editor.execution as Execution.Ready).target.sql)
    }

    @Test
    fun `an open question does not survive a change of connection`() = runTest {
        val editor = editor()
        editor.type("select 1|")
        editor.open("select 2")

        editor.show(connection(id = "id-2", name = "Other"))

        // The statement being offered came from a history row belonging to the
        // connection that is no longer here. Answering it afterwards would put one
        // server's query into the other server's editor.
        assertNull(editor.pendingScript)
        assertEquals("select 1", editor.text.text)
    }

    // --- Inserting a name from the browser ------------------------------------

    @Test
    fun `an inserted identifier lands at the caret with a space before it`() = runTest {
        val editor = editor()
        editor.type("select * from|")

        editor.insert("\"public\".\"users\"")

        assertEquals("select * from \"public\".\"users\"", editor.text.text)
        assertEquals(editor.text.text.length, editor.text.selection.start)
    }

    @Test
    fun `an inserted identifier replaces the selection and adds no space after an opening bracket`() =
        runTest {
            val editor = editor()
            editor.select("select count(x)", 13, 14)

            editor.insert("\"id\"")

            assertEquals("select count(\"id\")", editor.text.text)
        }
}
