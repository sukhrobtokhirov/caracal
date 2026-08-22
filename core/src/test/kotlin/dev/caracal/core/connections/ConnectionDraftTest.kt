package dev.caracal.core.connections

import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.FormKeys
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.redis.RedisEngine
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Validation, now that the fields are the engine's and the rules are split between
 * the two halves that can each be right about an engine they have never seen.
 *
 * The rules that follow from the declaration — required, numeric, in range, one of
 * these options — are core's, and the tests for them are written against whichever
 * engine happens to declare a field of that kind. The rules that need to know what
 * the engine is come back from the engine, and are asserted here through the same
 * entry point the form uses, because the split is only worth anything if the caller
 * cannot tell which half objected.
 */
class ConnectionDraftTest {
    private val postgres: DatabaseEngine = PostgresEngine()
    private val redis: DatabaseEngine = RedisEngine()

    private fun postgres(block: ConnectionDraft.() -> ConnectionDraft = { this }) =
        networkDraft(engineId = postgres.id, name = "Local", database = "caracal", username = "caracal")
            .block()

    private fun redis(block: ConnectionDraft.() -> ConnectionDraft = { this }) =
        networkDraft(engineId = redis.id, name = "Cache").block()

    /** Types one field, the way the form does. */
    private fun ConnectionDraft.typed(key: String, value: String) = copy(values = values + (key to value))

    private fun ConnectionDraft.engine(): DatabaseEngine = if (engineId == postgres.id) postgres else redis

    private fun ConnectionDraft.checked() = normalized(engine()).validate(engine())

    private fun ConnectionDraft.errorFields() = checked().map { it.field }

    private fun ConnectionDraft.normalized() = normalized(engine())

    @Test
    fun `applies the engine's declared default port`() {
        assertEquals("5432", postgres().normalized().values[FormKeys.PORT])
        assertEquals("6379", redis().normalized().values[FormKeys.PORT])
    }

    @Test
    fun `applies a declared default only where nothing was typed`() {
        assertEquals("0", redis().normalized().values[FormKeys.DATABASE])
        assertEquals("caracal", postgres().normalized().values[FormKeys.DATABASE])
    }

    @Test
    fun `drops a value the chosen engine never declared`() {
        // The form carries values across an engine switch, and a key the new engine
        // has no field for is one nothing will ever read or edit again.
        val carried = postgres().typed("sslrootcert", "/etc/ssl/root.pem").copy(engineId = redis.id)

        assertNull(carried.normalized(redis).values["sslrootcert"])
    }

    @Test
    fun `defaults the TLS mode and the environment`() {
        val normalized = postgres().normalized()

        assertEquals(TlsMode.DISABLE.wire, normalized.values[FormKeys.TLS])
        assertEquals(Environment.DEV, normalized.environment)
    }

    @Test
    fun `trims whitespace before validating, so a padded name is not a name`() {
        val normalized = postgres { copy(name = "  ") }.typed(FormKeys.HOST, "  localhost  ").normalized()

        assertEquals("", normalized.name)
        assertEquals("localhost", normalized.values[FormKeys.HOST])
        assertEquals(listOf(ValidationError.NAME), normalized.errorFields())
    }

    @Test
    fun `an empty color becomes absent rather than an invalid value`() {
        assertNull(postgres { copy(color = "   ") }.normalized().color)
        assertTrue(postgres { copy(color = "   ") }.checked().isEmpty())
    }

    @Test
    fun `a valid draft of either engine has nothing to report`() {
        assertTrue(postgres().checked().isEmpty())
        assertTrue(redis().checked().isEmpty())
    }

    @Test
    fun `reports every problem at once so the form can show them together`() {
        val errors = networkDraft(engineId = postgres.id, name = "", host = "", port = 0, username = "caracal")
            .errorFields()

        assertEquals(listOf(ValidationError.NAME, FormKeys.HOST, FormKeys.PORT, FormKeys.DATABASE), errors)
    }

    @Test
    fun `one message per field, whichever half objected`() {
        // An empty database is both a declared `required` and something PostgreSQL
        // has its own sentence about. The form has one place to put a message.
        val database = postgres { typed(FormKeys.DATABASE, "") }.checked()

        assertEquals(1, database.count { it.field == FormKeys.DATABASE })
    }

    @Test
    fun `rejects a host that is really a URL`() {
        listOf("postgres://db.example.com", "db.example.com/path", "user@db.example.com").forEach { host ->
            assertEquals(
                listOf(FormKeys.HOST),
                postgres { typed(FormKeys.HOST, host) }.errorFields(),
                "accepted $host",
            )
        }
    }

    @Test
    fun `accepts host names and literal addresses`() {
        listOf("localhost", "db-1.internal", "10.0.0.7", "::1", "2001:db8::1").forEach { host ->
            assertTrue(postgres { typed(FormKeys.HOST, host) }.checked().isEmpty(), "rejected $host")
        }
    }

    @Test
    fun `rejects a number outside its declared range`() {
        assertEquals(listOf(FormKeys.PORT), postgres { typed(FormKeys.PORT, "0") }.errorFields())
        assertEquals(listOf(FormKeys.PORT), postgres { typed(FormKeys.PORT, "70000") }.errorFields())
        assertTrue(postgres { typed(FormKeys.PORT, "65535") }.checked().isEmpty())
    }

    @Test
    fun `a PostgreSQL connection needs a database name`() {
        assertEquals(listOf(FormKeys.DATABASE), postgres { typed(FormKeys.DATABASE, "") }.errorFields())
    }

    @Test
    fun `a Redis database must be an index in range`() {
        assertEquals(listOf(FormKeys.DATABASE), redis { typed(FormKeys.DATABASE, "main") }.errorFields())
        assertEquals(listOf(FormKeys.DATABASE), redis { typed(FormKeys.DATABASE, "16") }.errorFields())
        assertTrue(redis { typed(FormKeys.DATABASE, "15") }.checked().isEmpty())
    }

    @Test
    fun `an engine is only offered the transport modes it declared`() {
        // Redis's form has two options and PostgreSQL's has three, so this is the
        // declared-choice rule rather than a rule about Redis.
        assertEquals(
            listOf(FormKeys.TLS),
            redis { typed(FormKeys.TLS, TlsMode.VERIFY_FULL.wire) }.errorFields(),
        )
        assertTrue(redis { typed(FormKeys.TLS, TlsMode.REQUIRE.wire) }.checked().isEmpty())
        assertTrue(postgres { typed(FormKeys.TLS, TlsMode.VERIFY_FULL.wire) }.checked().isEmpty())
    }

    @Test
    fun `a color must be a hex value`() {
        assertEquals(listOf(ValidationError.COLOR), postgres { copy(color = "red") }.errorFields())
        assertTrue(postgres { copy(color = "#4c8dff") }.checked().isEmpty())
    }

    @Test
    fun `rejects a name or host that is far too long`() {
        assertEquals(
            listOf(ValidationError.NAME),
            postgres { copy(name = "n".repeat(ConnectionDraft.MAX_NAME + 1)) }.errorFields(),
        )
        assertEquals(
            listOf(FormKeys.HOST),
            postgres { typed(FormKeys.HOST, "h".repeat(ConnectionDraft.MAX_HOST + 1)) }.errorFields(),
        )
    }

    @Test
    fun `an engine's own length limit is its own to state`() {
        assertEquals(
            listOf(FormKeys.DATABASE),
            postgres { typed(FormKeys.DATABASE, "d".repeat(PostgresEngine.MAX_DATABASE + 1)) }.errorFields(),
        )
    }

    @Test
    fun `an edit draft carries the saved fields and leaves the secret alone`() {
        val config = postgres { copy(environment = Environment.PROD, readOnly = true) }
            .normalized()
            .toConfig(postgres, ConnectionId("id-1"), Instant.parse("2026-08-20T10:00:00Z"))

        val draft = ConnectionDraft.of(config)

        assertEquals(config.name, draft.name)
        assertEquals(config.host, draft.values[FormKeys.HOST])
        assertEquals(config.port.toString(), draft.values[FormKeys.PORT])
        assertEquals(Environment.PROD, draft.environment)
        assertTrue(draft.readOnly)
        assertEquals(SecretUpdate.Unchanged, draft.secret)
    }

    @Test
    fun `the stored form keeps the target apart from the settings`() {
        val config = postgres { typed(FormKeys.USER, "reader") }
            .normalized()
            .toConfig(postgres, ConnectionId("id-1"), Instant.EPOCH)

        // The target holds what the connection points at; the settings hold what the
        // engine asked for and core does not interpret. A host in both would be two
        // answers to one question.
        assertEquals("localhost", config.host)
        assertNull(config.settings[FormKeys.HOST])
        assertEquals("reader", config.settings[FormKeys.USER])
        assertEquals("reader", config.username)
    }

    @Test
    fun `a password is never part of the stored settings`() {
        val config = postgres { typed(FormKeys.PASSWORD, "hunter2") }
            .normalized()
            .toConfig(postgres, ConnectionId("id-1"), Instant.EPOCH)

        assertNull(config.settings[FormKeys.PASSWORD])
        assertTrue(config.settings.values.none { it == "hunter2" })
    }

    @Test
    fun `toConfig keeps the identity and creation time it was given`() {
        val createdAt = Instant.parse("2026-08-20T10:00:00Z")

        val config = postgres().normalized().toConfig(postgres, ConnectionId("id-1"), createdAt)

        assertEquals(ConnectionId("id-1"), config.id)
        assertEquals(createdAt, config.createdAt)
        assertEquals(5432, config.port)
    }

    @Test
    fun `a Redis database index is read back as a number`() {
        val config = redis { typed(FormKeys.DATABASE, "7") }
            .normalized()
            .toConfig(redis, ConnectionId("id-1"), Instant.EPOCH)

        assertEquals(7, config.redisDatabaseIndex)
    }
}
