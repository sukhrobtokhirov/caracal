package dev.caracal.engine.postgres

import dev.caracal.engine.api.WriteIntent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Section 7's per-engine half for PostgreSQL.
 *
 * `StatementClassifierTest` covers what the classifier decides; this covers what
 * those decisions mean once they are an intent, which is the value core will gate
 * on. The interesting cases are the two arms that are not a straight rename.
 */
class PostgresIntentTest {

    @Test
    fun `a select reads`() {
        assertEquals(WriteIntent.READ_ONLY, PostgresIntent.classify("SELECT * FROM orders"))
    }

    @Test
    fun `an insert writes`() {
        assertEquals(WriteIntent.WRITE, PostgresIntent.classify("INSERT INTO t VALUES (1)"))
    }

    @Test
    fun `a data-modifying CTE writes, though it begins with WITH`() {
        assertEquals(
            WriteIntent.WRITE,
            PostgresIntent.classify("WITH gone AS (DELETE FROM t RETURNING *) SELECT * FROM gone"),
        )
    }

    @Test
    fun `a locking select writes, because a read-only transaction refuses it`() {
        listOf("FOR UPDATE", "FOR NO KEY UPDATE", "FOR SHARE", "FOR KEY SHARE").forEach { clause ->
            assertEquals(WriteIntent.WRITE, PostgresIntent.classify("SELECT * FROM t $clause"), clause)
        }
    }

    @Test
    fun `session control affects the connection and nothing else`() {
        // Not WRITE. Every statement runs in its own transaction that is rolled back
        // and PostgreSQL's SET is transactional, so this succeeds and is gone before
        // the next statement runs. Saying so is more use than refusing it.
        listOf("BEGIN", "SET search_path TO app", "DISCARD ALL", "LOCK TABLE t").forEach { sql ->
            assertEquals(WriteIntent.CONNECTION_AFFECTING, PostgresIntent.classify(sql), sql)
        }
    }

    @Test
    fun `session control does not count as a modification`() {
        assertFalse(PostgresIntent.classify("SET search_path TO app").modifies)
        assertFalse(PostgresIntent.classify("SELECT 1").modifies)
        assertTrue(PostgresIntent.classify("UPDATE t SET a = 1").modifies)
    }

    @Test
    fun `something unrecognizable is unknown, and never a read`() {
        listOf("", "   ", "-- just a comment", "gibberish nonsense").forEach { sql ->
            val intent = PostgresIntent.classify(sql)
            assertNotEquals(WriteIntent.READ_ONLY, intent, sql)
            assertEquals(WriteIntent.UNKNOWN, intent, sql)
        }
    }

    @Test
    fun `unknown counts as a modification, so core cannot wave it through`() {
        assertTrue(WriteIntent.UNKNOWN.modifies)
    }

    @Test
    fun `DDL is reported as a write rather than invented as DDL`() {
        // The classifier does not distinguish them: `INSERT` and `DROP TABLE` are both
        // "a modifying keyword appeared". A second keyword table here to tell them
        // apart would disagree with the first one the day either is edited.
        assertEquals(WriteIntent.WRITE, PostgresIntent.classify("DROP TABLE orders"))
        assertEquals(WriteIntent.WRITE, PostgresIntent.classify("CREATE INDEX i ON t (a)"))
    }

    @Test
    fun `EXPLAIN ANALYZE of a select is still a read`() {
        assertEquals(WriteIntent.READ_ONLY, PostgresIntent.classify("EXPLAIN ANALYZE SELECT * FROM orders"))
    }
}
