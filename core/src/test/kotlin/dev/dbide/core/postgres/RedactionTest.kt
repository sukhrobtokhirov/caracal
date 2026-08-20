package dev.dbide.core.postgres

import dev.dbide.core.connections.Secret
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

class RedactionTest {
    private val config = PostgresConnectionConfig(
        host = "db.internal.example",
        port = 6432,
        database = "payments",
        user = "reporting",
        password = Secret("hunter2"),
    )
    private val redaction = Redaction(config.secrets())

    @Test
    fun `removes host, database, user, and password`() {
        val scrubbed = redaction.scrub(
            "FATAL: password authentication failed for user \"reporting\" " +
                "connecting to db.internal.example:6432/payments with hunter2",
        )!!

        assertFalse(scrubbed.contains("db.internal.example"), scrubbed)
        assertFalse(scrubbed.contains("payments"), scrubbed)
        assertFalse(scrubbed.contains("reporting"), scrubbed)
        assertFalse(scrubbed.contains("hunter2"), scrubbed)
    }

    @Test
    fun `removes a JDBC URL even when the host is unknown to it`() {
        val scrubbed = Redaction.NONE.scrub("Failed to connect to jdbc:postgresql://elsewhere:5432/other now")

        assertEquals("Failed to connect to [redacted] now", scrubbed)
    }

    @Test
    fun `leaves unrelated text alone`() {
        assertEquals("relation \"orders\" does not exist", redaction.scrub("relation \"orders\" does not exist"))
    }

    @Test
    fun `passes null through`() {
        assertEquals(null, redaction.scrub(null))
    }

    @Test
    fun `ignores secrets too short to be distinctive`() {
        val single = Redaction(listOf("a", "postgres"))

        assertEquals("a value from [redacted]", single.scrub("a value from postgres"))
    }
}
