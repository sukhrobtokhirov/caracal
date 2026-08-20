package dev.dbide.core.postgres

import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.Secret
import dev.dbide.core.connections.TlsMode
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

class PostgresConnectionConfigTest {
    private val saved = ConnectionConfig(
        id = ConnectionId("id-1"),
        name = "Local",
        engine = Engine.POSTGRES,
        host = "localhost",
        port = 6432,
        database = "dbide",
        username = "dbide",
        tlsMode = TlsMode.REQUIRE,
        environment = Environment.DEV,
        readOnly = false,
        color = null,
        createdAt = Instant.parse("2026-08-20T10:00:00Z"),
    )

    @Test
    fun `builds the dialing form from a saved connection`() {
        val config = PostgresConnectionConfig.of(saved, Secret("hunter2"))

        assertEquals("localhost", config.host)
        assertEquals(6432, config.port)
        assertEquals("dbide", config.database)
        assertEquals("dbide", config.user)
        assertEquals(TlsMode.REQUIRE, config.tlsMode)
    }

    @Test
    fun `never puts the password in the JDBC URL`() {
        val config = PostgresConnectionConfig.of(saved, Secret("hunter2"))

        assertEquals("jdbc:postgresql://localhost:6432/dbide", config.jdbcUrl)
        assertFalse(config.jdbcUrl.contains("hunter2"))
    }

    @Test
    fun `does not print its own contents`() {
        val config = PostgresConnectionConfig.of(saved, Secret("hunter2"))

        val printed = "$config ${config.password}"

        assertFalse(printed.contains("hunter2"), printed)
        assertFalse(printed.contains("localhost"), printed)
    }

    @Test
    fun `lists everything redaction has to remove`() {
        val secrets = PostgresConnectionConfig.of(saved, Secret("hunter2")).secrets()

        listOf("localhost", "localhost:6432", "dbide", "hunter2", "jdbc:postgresql://localhost:6432/dbide")
            .forEach { assertEquals(true, it in secrets, "$it is not redacted") }
    }
}
