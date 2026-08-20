package dev.dbide.app

import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.export.CsvExportReport
import dev.dbide.core.export.ExportEligibility
import dev.dbide.core.export.ExportRefusal
import dev.dbide.core.export.ExportStop
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import dev.dbide.core.vault.VaultState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What Export does, and what it refuses to do.
 *
 * The eligibility rule itself belongs to `:core` and is tested there. What is
 * asserted here is everything that only exists once a person is clicking the
 * button: that a dismissed dialog is not a failure, that the statement exported is
 * the one that filled the grid rather than whatever has since been typed, that
 * cancelling leaves no file behind, and that the refusal is checked again on the
 * way through rather than trusted to a disabled button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    @TempDir
    lateinit var directory: Path

    private val id = ConnectionId("id-1")

    private fun service() = FakeConnectionService(VaultState.UNLOCKED)

    private val destination: Path get() = directory.resolve("invoices.csv")

    /** A chooser that always picks [destination], and records what it was offered. */
    private class Chooser(private val path: Path?) {
        val suggestions = mutableListOf<String>()

        val chooser: FileChooser = { suggestion ->
            suggestions += suggestion
            path
        }
    }

    private fun TestScope.export(
        service: FakeConnectionService = service(),
        chooser: FileChooser,
    ) = ExportViewModel(service, this, chooser)

    // --- The happy path -------------------------------------------------------

    @Test
    fun `an eligible statement is written to the chosen file`() = runTest {
        val service = service()
        service.exportContent = "id,total\r\n1,10\r\n"
        service.exportReport = CsvExportReport(rows = 1, bytes = 17, duration = 4.milliseconds)
        val chooser = Chooser(destination)
        val model = export(service, chooser.chooser)

        model.start(id, "SELECT id, total FROM invoices", "Local")
        advanceUntilIdle()

        val done = assertIs<ExportRun.Done>(model.run)
        assertEquals(destination, done.path)
        assertEquals(1L, done.report.rows)
        assertEquals(listOf("SELECT id, total FROM invoices" to destination), service.exports)
        assertEquals("id,total\r\n1,10\r\n", destination.readText())
    }

    @Test
    fun `the suggested name is one every platform will accept`() = runTest {
        val chooser = Chooser(destination)
        val model = export(chooser = chooser.chooser)

        // A connection name is a user's free text, and a file system is a poor place
        // to discover that one of them held a slash.
        model.start(id, "SELECT 1", "staging/eu: prod")
        advanceUntilIdle()

        // A path is not a name: everything before the last separator is dropped, and
        // the colon Finder still draws as a slash becomes an underscore.
        assertEquals(listOf("eu_ prod.csv"), chooser.suggestions)
    }

    @Test
    fun `the report says so when a limit stopped the file short`() = runTest {
        val service = service()
        service.exportReport = CsvExportReport(
            rows = 1_000_000,
            bytes = 64,
            duration = 4.milliseconds,
            stopped = ExportStop.ROW_LIMIT,
        )
        val model = export(service, Chooser(destination).chooser)

        model.start(id, "SELECT 1", "Local")
        advanceUntilIdle()

        val done = assertIs<ExportRun.Done>(model.run)
        assertFalse(done.report.complete)
        val text = ExportText.done(done.path, done.report)
        assertTrue("1,000,000 rows" in text, text)
        assertTrue("part of the result" in text, text)
    }

    // --- Refusals -------------------------------------------------------------

    @Test
    fun `a script of several statements is refused, and nothing is asked for`() = runTest {
        val service = service()
        val chooser = Chooser(destination)
        val model = export(service, chooser.chooser)

        model.start(id, "SELECT 1; SELECT 2", "Local")
        advanceUntilIdle()

        val failed = assertIs<ExportRun.Failed>(model.run)
        assertEquals(ExportRefusal.SEVERAL_STATEMENTS.message, failed.failure.message)
        // Refused before the dialog, not after it: a save dialog that appears and then
        // says no is a worse sentence than one that never appeared.
        assertTrue(chooser.suggestions.isEmpty())
        assertTrue(service.exports.isEmpty())
    }

    @Test
    fun `a write is refused even when the button that offered it should not have`() = runTest {
        val service = service()
        val model = export(service, Chooser(destination).chooser)

        // The strip disables the button for exactly this statement. Checking again
        // here is the point: a rule that lives only in an enabled-state holds until
        // someone adds a second way to press it.
        model.start(id, "DELETE FROM invoices", "Local")
        advanceUntilIdle()

        assertIs<ExportRun.Failed>(model.run)
        assertTrue(service.exports.isEmpty())
    }

    @Test
    fun `eligibility is asked of core and reported for the strip to explain`() = runTest {
        val model = export(chooser = Chooser(destination).chooser)

        val refused = assertIs<ExportEligibility.Refused>(model.eligibility("DELETE FROM invoices"))

        assertEquals(ExportRefusal.MODIFIES_DATA, refused.refusal)
        assertIs<ExportEligibility.Allowed>(model.eligibility("SELECT 1"))
    }

    // --- Dismissal, cancellation, and failure ---------------------------------

    @Test
    fun `dismissing the dialog is not a failure and writes nothing`() = runTest {
        val service = service()
        val model = export(service, Chooser(null).chooser)

        model.start(id, "SELECT 1", "Local")
        advanceUntilIdle()

        assertEquals(ExportRun.Idle, model.run)
        assertTrue(service.exports.isEmpty())
    }

    @Test
    fun `cancelling stops the export and leaves no half-written file`() = runTest {
        val service = service()
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        val model = export(service, Chooser(destination).chooser)

        model.start(id, "SELECT 1", "Local")
        advanceUntilIdle()
        assertIs<ExportRun.Running>(model.run)

        model.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ExportRun.Cancelled, model.run)
        // Deleting the partial file is `CsvExport.writeToFile`'s guarantee and is
        // asserted against the real thing in `:core`. What is this model's job is
        // that the work stopped and that the strip does not draw a stop as a failure.
        assertFalse(destination.exists(), "a cancelled export completed anyway")
    }

    @Test
    fun `a second Export while one is running is ignored`() = runTest {
        val service = service()
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        val chooser = Chooser(destination)
        val model = export(service, chooser.chooser)

        model.start(id, "SELECT 1", "Local")
        advanceUntilIdle()
        model.start(id, "SELECT 2", "Local")
        advanceUntilIdle()

        assertEquals(1, chooser.suggestions.size)
        assertEquals(listOf("SELECT 1"), service.exports.map { it.first })
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a server failure is shown as its own sentence`() = runTest {
        val service = service()
        service.nextFailure = DbException(DbError.ExportUnavailable("The statement returned no rows to export."))
        val model = export(service, Chooser(destination).chooser)

        model.start(id, "SELECT 1", "Local")
        advanceUntilIdle()

        val failed = assertIs<ExportRun.Failed>(model.run)
        assertEquals("export_unavailable", failed.failure.code)
        assertFalse(destination.exists(), "a failed export wrote a file anyway")
    }

    // --- Forgetting -----------------------------------------------------------

    @Test
    fun `a new result forgets the last report but not a running export`() = runTest {
        val service = service()
        val model = export(service, Chooser(destination).chooser)

        model.start(id, "SELECT 1", "Local")
        advanceUntilIdle()
        assertIs<ExportRun.Done>(model.run)

        model.forget()
        assertEquals(ExportRun.Idle, model.run)

        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        model.start(id, "SELECT 2", "Local")
        advanceUntilIdle()

        model.forget()

        // Still running: a new query is not a request to abandon a file the user asked
        // for two minutes ago.
        assertIs<ExportRun.Running>(model.run)
        gate.complete(Unit)
        advanceUntilIdle()
        assertIs<ExportRun.Done>(model.run)
    }

    @Test
    fun `locking stops a running export`() = runTest {
        val service = service()
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        val model = export(service, Chooser(destination).chooser)

        model.start(id, "SELECT 1", "Local")
        advanceUntilIdle()

        model.clear()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ExportRun.Idle, model.run)
        assertFalse(destination.exists(), "an export survived the vault locking")
    }

    @Test
    fun `the export goes to a directory the user chose, not one this code invented`() = runTest {
        val nested = Files.createDirectory(directory.resolve("reports")).resolve("q3.csv")
        val service = service()
        val model = export(service, Chooser(nested).chooser)

        model.start(id, "SELECT 1", "Local")
        advanceUntilIdle()

        assertEquals(nested, assertIs<ExportRun.Done>(model.run).path)
        assertTrue(nested.exists())
    }
}
