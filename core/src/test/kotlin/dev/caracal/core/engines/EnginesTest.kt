package dev.caracal.core.engines

import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionForm
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.DriverProvider
import dev.caracal.engine.api.DriverRequirement
import dev.caracal.engine.api.EngineCapabilities
import dev.caracal.engine.api.EngineId
import dev.caracal.engine.api.IntentClassifier
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.ValidationIssue
import dev.caracal.engine.api.WriteIntent
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.redis.RedisEngine
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The registry, and the rules it applies to whatever the classpath offers.
 *
 * The discovery half is asserted against the real service files, because a test that
 * folded over a hand-written list would pass with the registration deleted — and a
 * missing line in `META-INF/services` is precisely the mistake that makes an engine
 * vanish from a shipped build while every unit test stays green.
 */
class EnginesTest {

    @Test
    fun `the service loader finds the engines this build ships`() {
        val ids = Engines.all.map { it.id.value }
        assertTrue(PostgresEngine.ID.value in ids, "postgres is not registered: $ids")
        assertTrue(RedisEngine.ID.value in ids, "redis is not registered: $ids")
    }

    @Test
    fun `a stored engine resolves to the loaded instance, not a fresh one`() {
        // Every caller has to reach the same instance: an engine is free to hold
        // state — a driver it resolved, a pool built around it — and a second copy
        // would be a second one of whatever it holds.
        assertSame(Engines.byId(PostgresEngine.ID), Engines.require(PostgresEngine.ID))
        assertSame(Engines.byId(RedisEngine.ID), Engines.require(RedisEngine.ID))
    }

    @Test
    fun `an engine this build does not have is absent rather than a failure`() {
        assertNull(Engines.byId(EngineId("cassandra")))
    }

    @Test
    fun `engines are offered in a stable order`() {
        val names = Engines.all.map { it.displayName }
        assertEquals(names.sortedBy { it.lowercase() }, names)
    }

    @Test
    fun `two engines claiming one identifier leaves the first registered`() {
        val first = FakeEngine(EngineId("twin"), "First")
        val second = FakeEngine(EngineId("twin"), "Second")

        val registered = Engines.register(listOf(first, second))

        assertEquals(1, registered.size)
        assertSame(first, registered.single())
    }

    @Test
    fun `an engine added to the classpath needs no edit here to be offered`() {
        val added = FakeEngine(EngineId("aardvark"), "Aardvark")

        val registered = Engines.register(Engines.all + added)

        assertNotNull(registered.firstOrNull { it.id == added.id })
        assertEquals(Engines.all.size + 1, registered.size)
    }

    private class FakeEngine(
        override val id: EngineId,
        override val displayName: String,
    ) : DatabaseEngine {
        override val capabilities: EngineCapabilities = PostgresEngine.CAPABILITIES
        override val connectionForm: ConnectionForm = ConnectionForm(emptyList())
        override val driverRequirement: DriverRequirement? = null
        override val intentClassifier: IntentClassifier = IntentClassifier { WriteIntent.UNKNOWN }

        override fun validate(descriptor: ConnectionDescriptor): List<ValidationIssue> = emptyList()

        override suspend fun connect(
            descriptor: ConnectionDescriptor,
            secrets: SecretBundle,
            policy: SessionPolicy,
            drivers: DriverProvider,
        ): DatabaseSession = throw UnsupportedOperationException()
    }
}
