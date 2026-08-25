package dev.caracal.engine.sqlite

import dev.caracal.core.result.DbException
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionId
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.FormField
import dev.caracal.engine.api.FormKeys
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.WriteIntent
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The decisions this engine makes before a file is ever opened, and the one it makes
 * about creating one.
 *
 * `EngineDeclarationTest` in `:core` already folds every engine over the invariants
 * they share. What is left for here is what only SQLite has: a target that is a path,
 * a connection that may bring its own database into being, and a classifier that knows
 * one keyword the shared one does not.
 */
class SqliteEngineTest {

    @TempDir
    lateinit var directory: Path

    private val engine = SqliteEngine()

    @Test
    fun `a path that names nothing is a valid setting and a failed connection`() = runBlocking<Unit> {
        // The split this engine is deliberate about. Whether a file exists is a fact
        // about the disk at one instant, not a fact about the settings, so `validate`
        // — which a dialog runs on every keystroke — says nothing about it, and the
        // open is what answers. The alternative is a form that goes green and a
        // connection that fails anyway, which is worse than the form saying nothing.
        val missing = descriptor(directory.resolve("absent.db"))

        assertEquals(emptyList(), engine.validate(missing), "a path to a file that is not there was rejected")
        val failure = assertFailsWith<DbException> { engine.connect(missing, SecretBundle.None, policy()) }
        assertTrue(failure.error.message.contains("could not be opened"), failure.error.message)
    }

    @Test
    fun `creating the file is opt-in, and off it is an error`() = runBlocking<Unit> {
        // Not a safe default in either direction: off, a typo in a path is an error;
        // on, a typo is a new empty database that looks like the old one with
        // everything missing. Off is the one that fails loudly.
        val path = directory.resolve("new.db")

        assertFailsWith<DbException> {
            engine.connect(descriptor(path), SecretBundle.None, policy(readOnly = false))
        }
        assertTrue(!Files.exists(path), "a connection that was not asked to create a database created one")

        engine.connect(
            descriptor(path).copy(target = ConnectionTarget.File(path, createIfMissing = true)),
            SecretBundle.None,
            policy(readOnly = false),
        ).use { }

        assertTrue(Files.exists(path), "the connection was asked to create a database and did not")
    }

    @Test
    fun `the form's own spelling of create-if-missing reaches the connection`() = runBlocking<Unit> {
        // Two spellings of one decision, and both can arrive: the descriptor's flag is
        // what a target built in code carries, and the option is what the declared form
        // writes, because a `FormField.Toggle` produces an engine option and not a
        // field of `ConnectionTarget`. An engine that read only the first would have a
        // checkbox that did nothing.
        val path = directory.resolve("from-the-form.db")

        engine.connect(
            descriptor(path).copy(engineOptions = mapOf(SqliteEngine.OPTION_CREATE to "true")),
            SecretBundle.None,
            policy(readOnly = false),
        ).use { }

        assertTrue(Files.exists(path), "the form's checkbox did not reach the open mode")
    }

    @Test
    fun `a read-only connection never creates a database`() = runBlocking<Unit> {
        // Creating a file in order to open it read-only is a contradiction, and the
        // wrong resolution is to quietly create it: a read-only connection to a path
        // that names nothing must fail, or a mistyped path silently becomes an empty
        // database that reads as an empty one.
        val path = directory.resolve("read-only.db")

        assertFailsWith<DbException> {
            engine.connect(
                descriptor(path).copy(target = ConnectionTarget.File(path, createIfMissing = true)),
                SecretBundle.None,
                policy(readOnly = true),
            )
        }

        assertTrue(!Files.exists(path), "a read-only connection created a database")
    }

    @Test
    fun `a stored credential is refused rather than ignored`() = runBlocking<Unit> {
        // A descriptor can be built without going through the form — from a
        // hand-edited store, or from an engine switched in a dialog — and a password
        // the user believes is protecting this database is protecting nothing.
        // Ignoring it silently is how they find that out later.
        val path = seeded()

        val failure = assertFailsWith<DbException> {
            engine.connect(descriptor(path), SecretBundle.Password("hunter2".toCharArray()), policy())
        }

        assertTrue(failure.error.message.contains("not opened with a credential"), failure.error.message)
    }

    @Test
    fun `a file that is not a database fails at connect and not at the first query`() = runBlocking<Unit> {
        // `sqlite3_open` is lazy: it hands back a handle to anything, and the header
        // is not read until something asks for data. Without the round trip the
        // session makes on the way up, connecting to a JPEG would succeed and fail on
        // the user's first query instead — in the editor, blamed on their SQL.
        val notADatabase = directory.resolve("photo.jpg")
        Files.write(notADatabase, ByteArray(4096) { 0x42 })

        val failure = assertFailsWith<DbException> {
            engine.connect(descriptor(notADatabase), SecretBundle.None, policy())
        }

        assertTrue(failure.error.message.contains("not a SQLite database"), failure.error.message)
    }

    @Test
    fun `the form declares a path, a toggle, and no secret`() {
        // The declaration the conformance suite reads to decide whether the two
        // redaction cases apply to this engine. An engine that grew a secret field
        // without growing a redaction would start being asserted against, which is the
        // point of deriving it from the form rather than from a flag.
        val fields = engine.connectionForm.sections.flatMap { it.fields }

        assertEquals(listOf(FormKeys.PATH, SqliteEngine.OPTION_CREATE), fields.map { it.key })
        assertTrue(fields.none { it is FormField.Secret }, "SQLite declared a secret field")
    }

    @Test
    fun `REPLACE INTO is a write and the replace function is not`() {
        // The one keyword the shared classifier has never had to know. `REPLACE INTO`
        // is `INSERT OR REPLACE` and deletes the rows it conflicts with; `replace()`
        // is SQLite's string function and appears in the middle of a great many
        // selects. A classifier that matched the word anywhere would call the second a
        // write, which is the false positive that teaches people to click through.
        assertEquals(WriteIntent.WRITE, SqliteIntent.classify("REPLACE INTO t VALUES (1)"))
        assertEquals(WriteIntent.WRITE, SqliteIntent.classify("  replace\n  into t values (1)"))
        assertEquals(
            WriteIntent.READ_ONLY,
            SqliteIntent.classify("SELECT replace(name, 'a', 'b') FROM t"),
        )
        // A table whose name begins with the same seven letters is not the keyword.
        assertEquals(WriteIntent.UNKNOWN, SqliteIntent.classify("REPLACEMENTS"))
    }

    @Test
    fun `a PRAGMA is unknown rather than guessed at`() {
        // Some pragmas read and some rewrite the whole file, and telling them apart
        // means a table of pragma names that is wrong the next time SQLite gains one.
        // UNKNOWN is the answer that costs a confirmation dialog; the alternative
        // costs a database.
        assertEquals(WriteIntent.UNKNOWN, SqliteIntent.classify("PRAGMA journal_mode = WAL"))
        assertEquals(WriteIntent.UNKNOWN, SqliteIntent.classify("PRAGMA page_size"))
    }

    // ---------------------------------------------------------------- helpers

    private fun policy(readOnly: Boolean = true) =
        SessionPolicy(readOnly = readOnly, statementTimeout = 10.seconds)

    private fun descriptor(path: Path) = ConnectionDescriptor(
        id = ConnectionId("test"),
        engineId = SqliteEngine.ID,
        displayName = "Test",
        target = ConnectionTarget.File(path),
    )

    /** A real, if empty, SQLite database. */
    private fun seeded(): Path = directory.resolve("seeded.db").also { path ->
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { it.executeUpdate("CREATE TABLE t (id int)") }
        }
    }
}
