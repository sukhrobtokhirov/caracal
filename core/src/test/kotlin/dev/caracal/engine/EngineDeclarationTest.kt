package dev.caracal.engine

import dev.caracal.engine.api.CancellationSupport
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionId
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.EngineFamily
import dev.caracal.engine.api.FormField
import dev.caracal.engine.api.FormKeys
import dev.caracal.engine.api.NamespaceModel
import dev.caracal.engine.api.ReadOnlyEnforcement
import dev.caracal.core.engines.Engines
import dev.caracal.engine.api.TlsConfig
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.redis.RedisEngine
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /**
     * Every engine the classpath offers, not a list written here.
     *
     * Reading [Engines.all] is what makes this a fold over the engines that exist
     * rather than over the two that existed when it was written: an engine added to
     * the classpath is checked against every invariant below without this file being
     * opened, which is the same property Phase 3 gives the connection dialog.
     */
    private val engines: List<DatabaseEngine> = Engines.all

    private val postgres: DatabaseEngine = Engines.byId(PostgresEngine.ID)!!

    private val redis: DatabaseEngine = Engines.byId(RedisEngine.ID)!!

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
        //
        // An engine that dials no host has neither, and that is the same agreement
        // read the other way: a declared default port with no field to put it in is
        // as wrong as a field with the wrong number in it.
        engines.forEach { engine ->
            val port = engine.fields.filterIsInstance<FormField.Number>().firstOrNull { it.key == FormKeys.PORT }

            if (port == null) {
                assertNull(
                    engine.capabilities.defaultPort,
                    "${engine.id} declares a default port and no field to type one into",
                )
            } else {
                assertEquals(engine.capabilities.defaultPort, port.default, "${engine.id}")
            }
        }
    }

    @Test
    fun `read-only enforcement is declared honestly, engine by engine`() {
        // The disclosure that stops the connection list presenting two very different
        // guarantees with one badge. PostgreSQL's read-only connections are held to it
        // by the server, where a write hidden in a function body is still a write.
        // Redis's are held to it by an allowlist in this process.
        assertEquals(ReadOnlyEnforcement.SESSION_SETTING, postgres.capabilities.readOnlyEnforcement)
        assertEquals(ReadOnlyEnforcement.COMMAND_GUARD_ONLY, redis.capabilities.readOnlyEnforcement)
    }

    @Test
    fun `cancellation is declared honestly, engine by engine`() {
        // "Cancel that actually cancels" is a claim in the README, and CLIENT_ABANDON
        // is what makes it possible to keep that claim true while adding an engine
        // that cannot do it.
        assertEquals(CancellationSupport.OUT_OF_BAND, postgres.capabilities.cancellation)
        assertEquals(CancellationSupport.CLIENT_ABANDON, redis.capabilities.cancellation)
    }

    @Test
    fun `an engine refuses the kind of target it does not have`() {
        // Both directions, because both are reachable: a hand-edited store, and an
        // engine switched in a form. Neither engine kind may shrug and dial anyway.
        engines.forEach { engine ->
            val wrong = if (engine.dialsAFile) {
                descriptor(engine).copy(target = ConnectionTarget.Network("localhost", 1234, "app"))
            } else {
                descriptor(engine).copy(target = ConnectionTarget.File(Path.of("/tmp/whatever.db")))
            }

            assertTrue(engine.validate(wrong).isNotEmpty(), "${engine.id} accepted the wrong kind of target")
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
        engines.filterNot { it.dialsAFile }.forEach { engine ->
            val issues = engine.validate(descriptor(engine, port = 70_000))

            assertTrue(issues.any { it.field == FormKeys.PORT }, "${engine.id} accepted port 70000")
        }
    }

    @Test
    fun `an empty host is rejected, and says so about the host`() {
        engines.filterNot { it.dialsAFile }.forEach { engine ->
            val issues = engine.validate(descriptor(engine, host = "   "))

            assertTrue(issues.any { it.field == FormKeys.HOST }, "${engine.id} accepted a blank host")
        }
    }

    @Test
    fun `PostgreSQL needs a database and a user, because a connection is opened against one`() {
        val noDatabase = descriptor(postgres).copy(
            target = ConnectionTarget.Network("localhost", 5432, database = null),
        )
        assertTrue(postgres.validate(noDatabase).any { it.field == "database" })

        val noUser = descriptor(postgres).copy(engineOptions = emptyMap())
        assertTrue(postgres.validate(noUser).any { it.field == PostgresEngine.OPTION_USER })
    }

    @Test
    fun `a Redis database is a number, not a name`() {
        val named = descriptor(redis).copy(
            target = ConnectionTarget.Network("localhost", 6379, database = "analytics"),
        )

        assertTrue(redis.validate(named).any { it.field == "database" })
    }

    @Test
    fun `Redis will not encrypt without checking the certificate`() {
        // The mode that encrypts and accepts any certificate defends against nothing
        // an attacker on the path cannot do anyway, and offering it means somebody
        // picks it. The form does not offer it; validation refuses it too, because a
        // descriptor can be built without going through the form.
        val unverified = descriptor(redis).copy(tls = TlsConfig.Required(verifyHostname = false))

        assertTrue(redis.validate(unverified).any { it.field == "tls" })
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
        target = if (engine.dialsAFile) engine.fileTarget() else ConnectionTarget.Network(
            host = host,
            port = port,
            database = if (engine.capabilities.namespaceModel == NamespaceModel.DATABASE) "0" else "app",
        ),
        // Harmless to an engine that declares no user: an option nobody reads.
        engineOptions = mapOf(PostgresEngine.OPTION_USER to "caracal"),
    )

    /**
     * Whether this engine opens a file, read off the form rather than off its name.
     *
     * The distinction is a real one and the conformance suite has to make it without
     * a list: an engine with no host has no host to reject, and asserting that it
     * rejects a blank one would fail every file engine that ever exists.
     */
    private val DatabaseEngine.dialsAFile: Boolean
        get() = fields.any { it is FormField.FilePath }

    /** A path this engine would accept, in whichever extension it declared. */
    private fun DatabaseEngine.fileTarget(): ConnectionTarget.File {
        val field = fields.filterIsInstance<FormField.FilePath>().first()
        return ConnectionTarget.File(Path.of("/tmp/conformance${field.extensions.firstOrNull().orEmpty()}"))
    }

    /** Every field of every section, without depending on `:core`'s extension. */
    private val DatabaseEngine.fields: List<FormField>
        get() = connectionForm.sections.flatMap { it.fields }
}
