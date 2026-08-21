package dev.caracal.core.export

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Where an export goes, and what is left behind when it does not finish. */
class CsvExportTest {

    @TempDir
    lateinit var directory: Path

    // --- The file --------------------------------------------------------------

    @Test
    fun `the file is UTF-8 whatever the platform's default is`() = runBlocking {
        val path = directory.resolve("out.csv")

        CsvExport.writeToFile(path) { out -> out.append("ключ,значение\r\nnaïve,🙂\r\n") }

        // Read back as UTF-8 explicitly: the point is the bytes on disk, not what this
        // JVM would have guessed on the way in and out.
        assertEquals("ключ,значение\r\nnaïve,🙂\r\n", String(Files.readAllBytes(path), Charsets.UTF_8))
    }

    @Test
    fun `an export that fails leaves no file to be mistaken for a whole one`() = runBlocking {
        val path = directory.resolve("out.csv")

        assertThrows<IllegalStateException> {
            runBlocking {
                CsvExport.writeToFile(path) { out ->
                    out.append("id\r\n1\r\n")
                    error("the connection dropped")
                }
            }
        }

        assertFalse(Files.exists(path), "a partial CSV stops at a row and looks complete")
    }

    @Test
    fun `a cancelled export leaves no file either`() = runBlocking {
        // Cancellation is the ordinary way an export ends — the user changes their
        // mind about a million rows — so the delete has to survive being cancelled
        // itself rather than being skipped as more work.
        val path = directory.resolve("out.csv")
        val writing = CompletableDeferred<Unit>()

        val export = launch(Dispatchers.Default) {
            CsvExport.writeToFile(path) { out ->
                out.append("id\r\n1\r\n")
                writing.complete(Unit)
                awaitCancellation()
            }
        }
        writing.await()
        export.cancelAndJoin()

        assertFalse(Files.exists(path))
    }

    @Test
    fun `writing over an existing file replaces it rather than appending to it`() = runBlocking {
        val path = directory.resolve("out.csv")
        Files.writeString(path, "an older export that went on for pages")

        CsvExport.writeToFile(path) { out -> out.append("id\r\n") }

        assertEquals("id\r\n", path.readText())
    }

    // --- The name --------------------------------------------------------------

    @Test
    fun `an ordinary name gets an extension and nothing else`() {
        assertEquals("invoices.csv", CsvExport.fileName("invoices"))
        assertEquals("Sales 2026.csv", CsvExport.fileName("Sales 2026"))
    }

    @Test
    fun `a name that already ends in the extension does not collect a second one`() {
        assertEquals("invoices.csv", CsvExport.fileName("invoices.csv"))
        assertEquals("invoices.csv", CsvExport.fileName("invoices.CSV"))
        // Not an extension, just a dot in the middle of a name.
        assertEquals("2026.08.20.csv", CsvExport.fileName("2026.08.20"))
    }

    @Test
    fun `a path is reduced to a name in the directory the user picked`() {
        assertEquals("passwd.csv", CsvExport.fileName("../../etc/passwd"))
        assertEquals("system32.csv", CsvExport.fileName("C:\\Windows\\system32"))
        assertEquals("absolute.csv", CsvExport.fileName("/absolute"))
    }

    @Test
    fun `the characters some file system objects to are replaced on all of them`() {
        // Applied everywhere rather than per platform, so a file exported on a Mac can
        // be opened on the Windows machine it is about to be emailed to.
        assertEquals("q_ _.csv", CsvExport.fileName("q* ?"))
        assertEquals("10_11_12.csv", CsvExport.fileName("10:11:12"))
        assertEquals("a_b_c_d.csv", CsvExport.fileName("a<b>c|d"))
    }

    @Test
    fun `control characters do not reach the file system`() {
        assertEquals("line break.csv", CsvExport.fileName("line\nbreak"))
        assertEquals("nul_byte.csv", CsvExport.fileName("nul\u0000byte"))
    }

    @Test
    fun `a name Windows would resolve to a device is defused`() {
        // `CON.csv` is still the console, and writing an export to it is not a file
        // operation at all.
        assertEquals("_CON.csv", CsvExport.fileName("CON"))
        assertEquals("_nul.csv", CsvExport.fileName("nul"))
        assertEquals("_COM1.csv", CsvExport.fileName("COM1"))
        assertEquals("CONTRACTS.csv", CsvExport.fileName("CONTRACTS"))
    }

    @Test
    fun `trailing dots and spaces go, since Windows would drop them silently`() {
        // A name that is quietly changed on the way to disk is a name that reports one
        // file and creates another.
        assertEquals("report.csv", CsvExport.fileName("report..."))
        assertEquals("report.csv", CsvExport.fileName("report   "))
        // A leading dot would make the export a hidden file on Unix.
        assertEquals("report.csv", CsvExport.fileName(".report"))
    }

    @Test
    fun `runs of whitespace collapse`() {
        assertEquals("a b.csv", CsvExport.fileName("a \t\n b"))
    }

    @Test
    fun `a name with nothing usable left falls back to a name`() {
        assertEquals("export.csv", CsvExport.fileName(""))
        assertEquals("export.csv", CsvExport.fileName("   "))
        assertEquals("export.csv", CsvExport.fileName("///"))
        assertEquals("export.csv", CsvExport.fileName("..."))
    }

    @Test
    fun `a long name is cut short of every platform's limit, and never through a character`() {
        val name = CsvExport.fileName("🙂".repeat(200))

        assertTrue(name.length <= 84, "still $name characters long")
        // An odd number of UTF-16 units would mean a surrogate cut in half: not a
        // character, and not a name some file systems will accept at all.
        assertEquals(0, name.removeSuffix(".csv").length % 2)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }
}
