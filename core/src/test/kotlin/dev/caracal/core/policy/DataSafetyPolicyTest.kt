package dev.caracal.core.policy

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.sql.StatementKind
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * §2.4's table, asserted cell by cell.
 *
 * The policy is small enough that this could be read instead of tested. It is
 * tested because it is the thing standing between a typed `DELETE` and a
 * production server, and "small enough to read" is what everyone says about the
 * code that was wrong.
 */
class DataSafetyPolicyTest {

    // --- Reads and session statements pass everywhere -------------------------

    @ParameterizedTest(name = "{0} is granted on every connection")
    @ValueSource(
        strings = [
            "SELECT * FROM invoices",
            "  select 1  ",
            "TABLE invoices",
            "VALUES (1), (2)",
            "SHOW search_path",
            "EXPLAIN SELECT * FROM invoices",
            "EXPLAIN ANALYZE SELECT * FROM invoices",
            "WITH recent AS (SELECT * FROM invoices) SELECT count(*) FROM recent",
        ],
    )
    fun `a read is granted on every connection`(sql: String) {
        for (connection in everyConnection()) {
            assertEquals(
                Clearance.Granted,
                DataSafetyPolicy.clearanceFor(sql, connection),
                "$sql was not granted on ${connection.name}",
            )
        }
    }

    @ParameterizedTest(name = "{0} is granted on every connection")
    @ValueSource(strings = ["BEGIN", "SET search_path TO public", "DISCARD ALL", "ROLLBACK"])
    fun `a session statement is granted on every connection`(sql: String) {
        // Every statement runs in its own transaction, and on a read-only connection
        // that transaction is rolled back — so a `SET` has already been made harmless
        // by the time the policy could refuse it. Refusing it would only be refusing
        // something that does nothing.
        for (connection in everyConnection()) {
            assertEquals(
                Clearance.Granted,
                DataSafetyPolicy.clearanceFor(sql, connection),
                "$sql was not granted on ${connection.name}",
            )
        }
    }

    // --- Read-only connections refuse ----------------------------------------

    @ParameterizedTest(name = "{0} is refused on a read-only connection")
    @ValueSource(
        strings = [
            "DELETE FROM invoices",
            "UPDATE invoices SET total = 0",
            "INSERT INTO invoices (total) VALUES (1)",
            "TRUNCATE invoices",
            "DROP TABLE invoices",
            "ALTER TABLE invoices ADD COLUMN paid boolean",
            "GRANT SELECT ON invoices TO reporting",
            "WITH gone AS (DELETE FROM invoices RETURNING *) SELECT count(*) FROM gone",
            "SELECT * FROM invoices FOR UPDATE",
        ],
    )
    fun `a write is refused on a read-only connection`(sql: String) {
        val clearance = DataSafetyPolicy.clearanceFor(sql, connection(readOnly = true))

        val refused = assertIs<Clearance.Refused>(clearance)
        assertEquals(WriteRefusal.READ_ONLY_CONNECTION.code, refused.code)
    }

    @Test
    fun `an unrecognized statement is refused on a read-only connection`() {
        // §2.4's uncertainty rule. "We could not tell what this does" and "this
        // connection must not write" can only be resolved one way.
        val clearance = DataSafetyPolicy.clearanceFor("FROBNICATE invoices", connection(readOnly = true))

        val refused = assertIs<Clearance.Refused>(clearance)
        assertEquals(WriteRefusal.UNCLASSIFIED_ON_READ_ONLY.code, refused.code)
    }

    @Test
    fun `a read-only production connection refuses rather than asking`() {
        // Read-only outranks environment: there is nothing for a confirmation to
        // unlock, because the pool would refuse the statement on the server anyway.
        val clearance = DataSafetyPolicy.clearanceFor(
            "DELETE FROM invoices",
            connection(environment = Environment.PROD, readOnly = true),
        )

        assertIs<Clearance.Refused>(clearance)
    }

    // --- Writable connections confirm ----------------------------------------

    @ParameterizedTest(name = "a write on {0} needs one click")
    @ValueSource(strings = ["DEV", "STAGING"])
    fun `a write on a writable non-production connection needs a click`(environment: String) {
        val clearance = DataSafetyPolicy.clearanceFor(
            "DELETE FROM invoices",
            connection(environment = Environment.valueOf(environment)),
        )

        val confirm = assertIs<Clearance.Confirm>(clearance)
        assertEquals(Acknowledgement.CLICK, confirm.acknowledgement)
        assertEquals(StatementKind.WRITE, confirm.kind)
        assertNull(confirm.phrase, "a non-production write asked for a typed acknowledgement")
        assertTrue(confirm.satisfiedBy(""), "a click-only confirmation demanded input")
    }

    @Test
    fun `a write on production demands the connection name`() {
        val clearance = DataSafetyPolicy.clearanceFor(
            "UPDATE invoices SET total = 0",
            connection(name = "payments-prod", environment = Environment.PROD),
        )

        val confirm = assertIs<Clearance.Confirm>(clearance)
        assertEquals(Acknowledgement.TYPED, confirm.acknowledgement)
        assertEquals("payments-prod", confirm.phrase)
        assertEquals(Environment.PROD, confirm.environment)
    }

    @Test
    fun `an unrecognized statement on a writable connection is confirmed, not refused`() {
        val clearance = DataSafetyPolicy.clearanceFor("FROBNICATE invoices", connection())

        val confirm = assertIs<Clearance.Confirm>(clearance)
        // Carried so the dialog can say "not recognized" instead of claiming it is a
        // write, which would be a small lie and small lies are how warnings stop
        // being read.
        assertEquals(StatementKind.UNKNOWN, confirm.kind)
    }

    // --- What satisfies a typed acknowledgement -------------------------------

    @Test
    fun `a typed acknowledgement accepts surrounding whitespace and nothing else`() {
        val confirm = assertIs<Clearance.Confirm>(
            DataSafetyPolicy.clearanceFor(
                "DELETE FROM invoices",
                connection(name = "payments-prod", environment = Environment.PROD),
            ),
        )

        assertTrue(confirm.satisfiedBy("payments-prod"))
        assertTrue(confirm.satisfiedBy("  payments-prod\n"), "a pasted name with a newline was rejected")

        assertFalse(confirm.satisfiedBy(""))
        assertFalse(confirm.satisfiedBy("payments"))
        // Not case-folded: the name is on screen to be copied, and accepting a
        // different capitalization would accept a name the user was not looking at.
        assertFalse(confirm.satisfiedBy("PAYMENTS-PROD"))
        assertFalse(confirm.satisfiedBy("payments-prod2"))
    }

    // --- Fixtures -------------------------------------------------------------

    private fun everyConnection() = listOf(
        connection(name = "dev", environment = Environment.DEV),
        connection(name = "staging", environment = Environment.STAGING),
        connection(name = "prod", environment = Environment.PROD),
        connection(name = "dev-ro", environment = Environment.DEV, readOnly = true),
        connection(name = "prod-ro", environment = Environment.PROD, readOnly = true),
    )

    private fun connection(
        name: String = "local",
        environment: Environment = Environment.DEV,
        readOnly: Boolean = false,
    ) = ConnectionConfig(
        id = ConnectionId("id-1"),
        name = name,
        engine = Engine.POSTGRES,
        host = "localhost",
        port = 5432,
        database = "caracal",
        username = "caracal",
        tlsMode = TlsMode.DISABLE,
        environment = environment,
        readOnly = readOnly,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )
}
