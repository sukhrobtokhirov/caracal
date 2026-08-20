package dev.dbide.core.postgres

import dev.dbide.core.connections.Secret
import dev.dbide.core.result.DbError
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PostgresErrorsTest {
    private val config = PostgresConnectionConfig(
        host = "db.internal.example",
        port = 6432,
        database = "payments",
        user = "reporting",
        password = Secret("hunter2"),
    )
    private val redaction = Redaction(config.secrets())

    @ParameterizedTest(name = "SQLSTATE {0} is {1}")
    @CsvSource(
        "28P01, authentication_failed",
        "28000, authentication_failed",
        "3D000, database_not_found",
        "08001, host_unreachable",
        "08S01, host_unreachable",
        "08006, connection_failed",
        "08004, connection_failed",
        "57P03, connection_failed",
        "57014, cancelled",
        "25006, read_only_violation",
        "42P01, query_failed",
    )
    fun `maps SQLSTATE onto a distinct case`(sqlState: String, expectedCode: String) {
        val error = PostgresErrors.classify(SQLException("driver text", sqlState), redaction)

        assertEquals(expectedCode, error.code)
    }

    @Test
    fun `a pool timeout inherits the state of the failure underneath it`() {
        val poolTimeout = SQLTransientConnectionException(
            "Connection is not available, request timed out after 5000ms",
            null as String?,
            SQLException("connection refused", "08001"),
        )

        assertIs<DbError.HostUnreachable>(PostgresErrors.classify(poolTimeout, redaction))
    }

    @Test
    fun `an exception wrapped by another library is still classified`() {
        val wrapped = RuntimeException("pool failed", SQLException("bad password", "28P01"))

        assertIs<DbError.AuthenticationFailed>(PostgresErrors.classify(wrapped, redaction))
    }

    @Test
    fun `a timeout beats the cancellation state it arrives as`() {
        val cancelled = SQLException("ERROR: canceling statement due to statement timeout", "57014")

        assertIs<DbError.Timeout>(PostgresErrors.classify(cancelled, redaction, timedOut = true))
        assertIs<DbError.Timeout>(PostgresErrors.classify(SQLTimeoutException("timed out"), redaction))
    }

    @Test
    fun `a failure with no SQLException anywhere is a connection failure`() {
        assertIs<DbError.ConnectionFailed>(PostgresErrors.classify(IllegalStateException("no idea")))
    }

    @Test
    fun `connection errors never carry the connection identity`() {
        val leaky = SQLException(
            "FATAL: password authentication failed for user \"reporting\" " +
                "(jdbc:postgresql://db.internal.example:6432/payments)",
            "28P01",
        )

        val message = PostgresErrors.classify(leaky, redaction).message

        assertNoConnectionIdentity(message)
    }

    @Test
    fun `query errors keep the server text but lose the connection identity`() {
        val leaky = SQLException(
            "relation \"orders\" does not exist on db.internal.example as user reporting",
            "42P01",
        )

        val error = PostgresErrors.classify(leaky, redaction)

        assertIs<DbError.QueryFailed>(error)
        assertEquals("42P01", error.sqlState)
        assertNoConnectionIdentity(error.message)
        assertEquals(true, error.message.contains("relation \"orders\" does not exist"))
    }

    private fun assertNoConnectionIdentity(message: String) {
        listOf("db.internal.example", "6432", "payments", "reporting", "hunter2", "jdbc:").forEach {
            assertFalse(message.contains(it, ignoreCase = true), "leaked \"$it\" in: $message")
        }
    }
}
