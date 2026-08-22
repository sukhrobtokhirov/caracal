package dev.caracal.core.postgres

import dev.caracal.core.connections.Secret
import dev.caracal.core.connections.TlsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

/**
 * The dialing form's two promises: the URL carries no credentials, and the object
 * cannot print any.
 *
 * It used to also assert the mapping from a saved connection, through a
 * `PostgresConnectionConfig.of` that no longer exists — Phase 3 left `PostgresEngine`
 * as the only place a descriptor becomes a dialing form, and that mapping is asserted
 * where it now lives, in `PostgresEngineIntegrationTest` and `DescriptorsTest`.
 */
class PostgresConnectionConfigTest {
    private val config = PostgresConnectionConfig(
        host = "localhost",
        port = 6432,
        database = "caracal",
        user = "caracal",
        password = Secret("hunter2"),
        tlsMode = TlsMode.REQUIRE,
        readOnly = false,
    )

    @Test
    fun `never puts the password in the JDBC URL`() {
        assertEquals("jdbc:postgresql://localhost:6432/caracal", config.jdbcUrl)
        assertFalse(config.jdbcUrl.contains("hunter2"))
    }

    @Test
    fun `does not print its own contents`() {
        val printed = "$config ${config.password}"

        assertFalse(printed.contains("hunter2"), printed)
        assertFalse(printed.contains("localhost"), printed)
    }

    @Test
    fun `lists everything redaction has to remove`() {
        val secrets = config.secrets()

        listOf("localhost", "localhost:6432", "caracal", "hunter2", "jdbc:postgresql://localhost:6432/caracal")
            .forEach { assertEquals(true, it in secrets, "$it is not redacted") }
    }
}
