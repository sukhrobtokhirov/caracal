package dev.dbide.core.connections

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ConnectionDraftTest {
    private fun postgres(block: ConnectionDraft.() -> ConnectionDraft = { this }) =
        ConnectionDraft(name = "Local", engine = Engine.POSTGRES, host = "localhost", database = "dbide")
            .block()

    private fun redis(block: ConnectionDraft.() -> ConnectionDraft = { this }) =
        ConnectionDraft(name = "Cache", engine = Engine.REDIS, host = "localhost").block()

    private fun ConnectionDraft.errorFields() = validate().map { it.field }

    @Test
    fun `applies the engine default port`() {
        assertEquals(5432, postgres().normalized().port)
        assertEquals(6379, redis().normalized().port)
    }

    @Test
    fun `defaults a Redis database to index zero and leaves PostgreSQL alone`() {
        assertEquals("0", redis().normalized().database)
        assertEquals("dbide", postgres().normalized().database)
    }

    @Test
    fun `defaults the TLS mode and the environment`() {
        val normalized = postgres().normalized()

        assertEquals(TlsMode.DISABLE, normalized.tlsMode)
        assertEquals(Environment.DEV, normalized.environment)
    }

    @Test
    fun `trims whitespace before validating, so a padded name is not a name`() {
        val normalized = postgres { copy(name = "  ", host = "  localhost  ") }.normalized()

        assertEquals("", normalized.name)
        assertEquals("localhost", normalized.host)
        assertEquals(listOf(ValidationError.NAME), normalized.errorFields())
    }

    @Test
    fun `an empty color becomes absent rather than an invalid value`() {
        assertNull(postgres { copy(color = "   ") }.normalized().color)
        assertTrue(postgres { copy(color = "   ") }.normalized().validate().isEmpty())
    }

    @Test
    fun `a valid draft of either engine has nothing to report`() {
        assertTrue(postgres().normalized().validate().isEmpty())
        assertTrue(redis().normalized().validate().isEmpty())
    }

    @Test
    fun `reports every problem at once so the form can show them together`() {
        val errors = ConnectionDraft(name = "", engine = Engine.POSTGRES, host = "", port = 0)
            .normalized()
            .errorFields()

        assertEquals(
            listOf(ValidationError.NAME, ValidationError.HOST, ValidationError.PORT, ValidationError.DATABASE),
            errors,
        )
    }

    @Test
    fun `rejects a host that is really a URL`() {
        assertEquals(
            listOf(ValidationError.HOST),
            postgres { copy(host = "postgres://db.example.com") }.normalized().errorFields(),
        )
        assertEquals(
            listOf(ValidationError.HOST),
            postgres { copy(host = "db.example.com/path") }.normalized().errorFields(),
        )
        assertEquals(
            listOf(ValidationError.HOST),
            postgres { copy(host = "user@db.example.com") }.normalized().errorFields(),
        )
    }

    @Test
    fun `accepts host names and literal addresses`() {
        listOf("localhost", "db-1.internal", "10.0.0.7", "::1", "2001:db8::1").forEach { host ->
            assertTrue(
                postgres { copy(host = host) }.normalized().validate().isEmpty(),
                "rejected $host",
            )
        }
    }

    @Test
    fun `rejects a port outside the usable range`() {
        assertEquals(listOf(ValidationError.PORT), postgres { copy(port = 0) }.normalized().errorFields())
        assertEquals(listOf(ValidationError.PORT), postgres { copy(port = 70000) }.normalized().errorFields())
        assertTrue(postgres { copy(port = 65535) }.normalized().validate().isEmpty())
    }

    @Test
    fun `a PostgreSQL connection needs a database name`() {
        assertEquals(
            listOf(ValidationError.DATABASE),
            postgres { copy(database = "") }.normalized().errorFields(),
        )
    }

    @Test
    fun `a Redis database must be an index in range`() {
        assertEquals(
            listOf(ValidationError.DATABASE),
            redis { copy(database = "main") }.normalized().errorFields(),
        )
        assertEquals(
            listOf(ValidationError.DATABASE),
            redis { copy(database = "16") }.normalized().errorFields(),
        )
        assertTrue(redis { copy(database = "15") }.normalized().validate().isEmpty())
    }

    @Test
    fun `Redis does not offer verify-full`() {
        assertEquals(
            listOf(ValidationError.TLS_MODE),
            redis { copy(tlsMode = TlsMode.VERIFY_FULL) }.normalized().errorFields(),
        )
        assertTrue(redis { copy(tlsMode = TlsMode.REQUIRE) }.normalized().validate().isEmpty())
        assertTrue(postgres { copy(tlsMode = TlsMode.VERIFY_FULL) }.normalized().validate().isEmpty())
    }

    @Test
    fun `a color must be a hex value`() {
        assertEquals(
            listOf(ValidationError.COLOR),
            postgres { copy(color = "red") }.normalized().errorFields(),
        )
        assertTrue(postgres { copy(color = "#4c8dff") }.normalized().validate().isEmpty())
    }

    @Test
    fun `rejects a name or host that is far too long`() {
        assertEquals(
            listOf(ValidationError.NAME),
            postgres { copy(name = "n".repeat(ConnectionDraft.MAX_NAME + 1)) }.normalized().errorFields(),
        )
        assertEquals(
            listOf(ValidationError.HOST),
            postgres { copy(host = "h".repeat(ConnectionDraft.MAX_HOST + 1)) }.normalized().errorFields(),
        )
    }

    @Test
    fun `an edit draft carries the saved fields and leaves the secret alone`() {
        val config = postgres { copy(environment = Environment.PROD, readOnly = true) }
            .normalized()
            .toConfig(ConnectionId("id-1"), Instant.parse("2026-08-20T10:00:00Z"))

        val draft = ConnectionDraft.of(config)

        assertEquals(config.name, draft.name)
        assertEquals(config.host, draft.host)
        assertEquals(config.port, draft.port)
        assertEquals(Environment.PROD, draft.environment)
        assertTrue(draft.readOnly)
        assertEquals(SecretUpdate.Unchanged, draft.secret)
    }

    @Test
    fun `toConfig keeps the identity and creation time it was given`() {
        val createdAt = Instant.parse("2026-08-20T10:00:00Z")

        val config = postgres().normalized().toConfig(ConnectionId("id-1"), createdAt)

        assertEquals(ConnectionId("id-1"), config.id)
        assertEquals(createdAt, config.createdAt)
        assertEquals(5432, config.port)
    }

    @Test
    fun `a Redis database index is read back as a number`() {
        val config = redis { copy(database = "7") }
            .normalized()
            .toConfig(ConnectionId("id-1"), Instant.EPOCH)

        assertEquals(7, config.redisDatabaseIndex)
    }
}
