package dev.caracal.engine

import dev.caracal.engine.api.CancellationSupport
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionId
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.EngineFamily
import dev.caracal.engine.api.FormField
import dev.caracal.engine.api.NamespaceModel
import dev.caracal.engine.api.ReadOnlyEnforcement
import dev.caracal.engine.api.TlsConfig
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.redis.RedisEngine
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * What every engine has to be able to say about itself, asserted over all of them at
 * once rather than one file at a time.
 *
 * This is the seed of the conformance suite in section 10, and it is deliberately
 * written as a fold over [engines] rather than as two parallel test classes. An
 * invariant that is checked per engine is an invariant the third engine will not be
 * checked against, and five engines becoming five rotting codebases is the failure
 * this whole refactor is meant to avoid.
 */
class EngineDeclarationTest {

    private val engines: List<DatabaseEngine> = listOf(PostgresEngine, RedisEngine)

    @Test
    fun `every engine has a distinct, non-empty identity`() {
        engines.forEach { engine ->
            assertTrue(engine.id.value.isNotBlank(), "an engine with no id")
            assertTrue(engine.displayName.isNotBlank(), "${engine.id} has no display name")
        }
        assertEquals(engines.size, engines.map { it.id }.distinct().size, "two engines share an id")
    }

    @Test
    fun `a driver is only provisioned at runtime for a SQL engine`() {
        // Section 8.2, as a test rather than as a paragraph. Runtime provisioning
        // reaches a driver reflectively across a classloader boundary, which works
        // because `java.sql.Driver` is a stable surface. A Lettuce client has no such
        // surface, so a non-JDBC engine that declared a requirement would be
        // describing a download that could never be loaded.
        engines.forEach { engine ->
            if (engine.driverRequirement != null) {
                assertEquals(
                    EngineFamily.SQL,
                    engine.capabilities.family,
                    "${engine.id} wants a runtime driver but is not a JDBC engine",
                )
            }
        }
    }

    @Test
    fun `no form asks for the same value twice`() {
        engines.forEach { engine ->
            val keys = engine.connectionForm.sections.flatMap { section -> section.fields.map { it.key } }
            assertEquals(
                keys.size,
                keys.distinct().size,
                "${engine.id} has a duplicate field key: ${keys.groupBy { it }.filterValues { it.size > 1 }.keys}",
            )
            assertTrue(keys.isNotEmpty(), "${engine.id} offers no way to describe a connection")
        }
    }

    @Test
    fun `the port the form suggests is the port the capabilities declare`() {
        // Two places that have to agree, and nothing else makes them. A form that
        // suggests 5432 for an engine whose default is 3306 is the sort of thing
        // nobody notices until a connection dialog is wrong for a whole release.
        engines.forEach { engine ->
            val port = engine.connectionForm.sections
                .flatMap { it.fields }
                .filterIsInstance<FormField.Number>()
                .firstOrNull { it.key == "port" }

            assertNotNull(port, "${engine.id} has no port field")
            assertEquals(engine.capabilities.defaultPort, port.default, "${engine.id}")
        }
    }

    @Test
    fun `read-only enforcement is declared honestly, engine by engine`() {
        // The disclosure that stops the connection list presenting two very different
        // guarantees with one badge. PostgreSQL's read-only connections are held to it
        // by the server, where a write hidden in a function body is still a write.
        // Redis's are held to it by an allowlist in this process.
        assertEquals(ReadOnlyEnforcement.SESSION_SETTING, PostgresEngine.capabilities.readOnlyEnforcement)
        assertEquals(ReadOnlyEnforcement.COMMAND_GUARD_ONLY, RedisEngine.capabilities.readOnlyEnforcement)
    }

    @Test
    fun `cancellation is declared honestly, engine by engine`() {
        // "Cancel that actually cancels" is a claim in the README, and CLIENT_ABANDON
        // is what makes it possible to keep that claim true while adding an engine
        // that cannot do it.
        assertEquals(CancellationSupport.OUT_OF_BAND, PostgresEngine.capabilities.cancellation)
        assertEquals(CancellationSupport.CLIENT_ABANDON, RedisEngine.capabilities.cancellation)
    }

    @Test
    fun `an engine that dials a host refuses a file`() {
        val file = descriptor(PostgresEngine).copy(
            target = ConnectionTarget.File(Path.of("/tmp/whatever.db")),
        )

        engines.forEach { engine ->
            val issues = engine.validate(file.copy(engineId = engine.id))
            assertTrue(issues.isNotEmpty(), "${engine.id} accepted a file path")
        }
    }

    @Test
    fun `a well-formed descriptor is accepted by the engine it names`() {
        engines.forEach { engine ->
            assertEquals(emptyList(), engine.validate(descriptor(engine)), "${engine.id}")
        }
    }

    @Test
    fun `a port outside the range is rejected, and says so about the port`() {
        engines.forEach { engine ->
            val issues = engine.validate(descriptor(engine, port = 70_000))

            assertTrue(issues.any { it.field == "port" }, "${engine.id} accepted port 70000")
        }
    }

    @Test
    fun `an empty host is rejected, and says so about the host`() {
        engines.forEach { engine ->
            val issues = engine.validate(descriptor(engine, host = "   "))

            assertTrue(issues.any { it.field == "host" }, "${engine.id} accepted a blank host")
        }
    }

    @Test
    fun `PostgreSQL needs a database and a user, because a connection is opened against one`() {
        val noDatabase = descriptor(PostgresEngine).copy(
            target = ConnectionTarget.Network("localhost", 5432, database = null),
        )
        assertTrue(PostgresEngine.validate(noDatabase).any { it.field == "database" })

        val noUser = descriptor(PostgresEngine).copy(engineOptions = emptyMap())
        assertTrue(PostgresEngine.validate(noUser).any { it.field == PostgresEngine.OPTION_USER })
    }

    @Test
    fun `a Redis database is a number, not a name`() {
        val named = descriptor(RedisEngine).copy(
            target = ConnectionTarget.Network("localhost", 6379, database = "analytics"),
        )

        assertTrue(RedisEngine.validate(named).any { it.field == "database" })
    }

    @Test
    fun `Redis will not encrypt without checking the certificate`() {
        // The mode that encrypts and accepts any certificate defends against nothing
        // an attacker on the path cannot do anyway, and offering it means somebody
        // picks it. The form does not offer it; validation refuses it too, because a
        // descriptor can be built without going through the form.
        val unverified = descriptor(RedisEngine).copy(tls = TlsConfig.Required(verifyHostname = false))

        assertTrue(RedisEngine.validate(unverified).any { it.field == "tls" })
    }

    /**
     * A descriptor an engine should be happy with.
     *
     * The database is the one field that cannot be written once for every engine: a
     * PostgreSQL connection opens against a named database, and a Redis one selects a
     * numbered one. That difference is exactly what `namespaceModel` is for, so the
     * fixture reads it from there rather than from the engine's name.
     */
    private fun descriptor(
        engine: DatabaseEngine,
        host: String = "localhost",
        port: Int = engine.capabilities.defaultPort ?: 0,
    ) = ConnectionDescriptor(
        id = ConnectionId("test"),
        engineId = engine.id,
        displayName = "Test",
        target = ConnectionTarget.Network(
            host = host,
            port = port,
            database = if (engine.capabilities.namespaceModel == NamespaceModel.DATABASE) "0" else "app",
        ),
        engineOptions = mapOf(PostgresEngine.OPTION_USER to "caracal"),
    )
}
