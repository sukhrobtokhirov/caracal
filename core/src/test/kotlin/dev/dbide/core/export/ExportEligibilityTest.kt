package dev.dbide.core.export

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

/**
 * Which statements may be exported, given that exporting one runs it again.
 *
 * The read-only transaction is what makes a write impossible; this is what makes
 * the refusal explainable, and it is why the bar is higher than it is for running
 * a statement once. A `SELECT` may be looked at and exported. A statement that
 * modifies data was allowed to be *attempted* — the server refused it — and there
 * is nothing to gain from attempting it a second time on the way to a file.
 */
class ExportEligibilityTest {

    @Test
    fun `a read is allowed, and carries the statement that will run`() {
        val eligibility = ExportEligibility.of("  select * from invoices;  ")

        val allowed = assertIs<ExportEligibility.Allowed>(eligibility)
        // Trimmed to what will be sent, and still pointing back at the script it came
        // from, so an error position maps onto the editor as it does for a run.
        assertEquals("select * from invoices;", allowed.statement.text)
        assertEquals(2, allowed.statement.start)
    }

    @Test
    fun `the reads that do not begin with SELECT are allowed too`() {
        for (sql in listOf("TABLE invoices", "VALUES (1), (2)", "WITH t AS (SELECT 1) SELECT * FROM t", "SHOW timezone", "EXPLAIN SELECT 1")) {
            assertIs<ExportEligibility.Allowed>(ExportEligibility.of(sql), "refused $sql")
        }
    }

    @Test
    fun `a statement that modifies data is refused`() {
        assertRefused(ExportRefusal.MODIFIES_DATA, "delete from invoices")
        assertRefused(ExportRefusal.MODIFIES_DATA, "update invoices set total = 0")
        // The write is inside a CTE, and the statement begins with a read.
        assertRefused(ExportRefusal.MODIFIES_DATA, "with gone as (delete from invoices returning *) select * from gone")
        // A maintenance command rewrites what it touches, which is a modification the
        // classifier names for the same reason.
        assertRefused(ExportRefusal.MODIFIES_DATA, "vacuum full invoices")
    }

    @Test
    fun `a session statement is refused, since it has no rows to write`() {
        assertRefused(ExportRefusal.SESSION_STATEMENT, "set search_path to sales")
        assertRefused(ExportRefusal.SESSION_STATEMENT, "begin")
    }

    @Test
    fun `a statement the classifier does not recognize is refused`() {
        // The classifier errs toward not knowing, and export is the wrong place to
        // find out what an unfamiliar command does by running it twice.
        assertRefused(ExportRefusal.UNRECOGNIZED_STATEMENT, "wibble invoices")
        assertRefused(ExportRefusal.UNRECOGNIZED_STATEMENT, "refresh_everything now")
    }

    @Test
    fun `an empty script has nothing to export`() {
        assertRefused(ExportRefusal.NOTHING_TO_EXPORT, "")
        assertRefused(ExportRefusal.NOTHING_TO_EXPORT, "   \n  ")
        assertRefused(ExportRefusal.NOTHING_TO_EXPORT, "-- just a comment\n")
    }

    @Test
    fun `a script of several statements is refused`() {
        // Export runs one statement. Picking which of three the user meant is a
        // question the editor answers when it runs them, not one to guess at here.
        assertRefused(ExportRefusal.SEVERAL_STATEMENTS, "select 1; select 2")
    }

    @Test
    fun `a script that does not close what it opened is refused`() {
        // The splitter is right that everything before the error is runnable. It is
        // still a script in the middle of being typed, and what the rest of it means
        // is not known — including whether the part before it is the part meant.
        assertRefused(ExportRefusal.UNFINISHED_STATEMENT, "select 'unterminated")
        assertRefused(ExportRefusal.UNFINISHED_STATEMENT, "select 1; select 'unterminated")
        assertRefused(ExportRefusal.UNFINISHED_STATEMENT, "select 1 /* unclosed")
    }

    @Test
    fun `a semicolon inside a value does not make a script of several`() {
        assertIs<ExportEligibility.Allowed>(ExportEligibility.of("select 'a; b' as text"))
    }

    @Test
    fun `every refusal says something a user can act on`() {
        for (refusal in ExportRefusal.entries) {
            assertEquals(refusal.message.trim(), refusal.message, "${refusal.name} is padded")
            assert(refusal.message.endsWith(".")) { "${refusal.name} is not a sentence" }
        }
    }

    private fun assertRefused(expected: ExportRefusal, sql: String) {
        val refused = assertIs<ExportEligibility.Refused>(ExportEligibility.of(sql), "allowed $sql")
        assertEquals(expected, refused.refusal, "wrong reason for $sql")
    }
}
