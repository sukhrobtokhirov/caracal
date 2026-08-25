package dev.caracal.engine.conformance

import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.postgres.PostgresConformanceFixture
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/**
 * The suite is checked against an engine that should fail it.
 *
 * Green is not evidence. A conformance suite that both bundled engines pass tells you
 * nothing about whether it would catch a third engine getting something wrong, and the
 * failure mode is not hypothetical: `ArchitectureTest` passed for weeks with a
 * forbidden-import list that matched nothing. So the suite is run against
 * [LeakyEngine] — PostgreSQL with a password in its error messages and no read-only
 * enforcement — and the results are asserted.
 *
 * The assertion is on the *exact* set of failures rather than on "at least these two",
 * and that is the stronger half. It says the suite is sensitive to the two defects and
 * that it is not simply failing everything, which a suite that had grown too strict
 * would also do while looking correct here.
 *
 * The run happens through [EngineTestKit] rather than by letting Gradle discover
 * [LeakyEngineConformance], because a class whose whole purpose is to fail cannot be
 * allowed into an ordinary build. The `negative` tag keeps it out; see the root
 * `build.gradle.kts`.
 */
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class BrokenEngineIsCaughtTest {

    @Test
    fun `an engine that leaks its password and ignores read-only fails the suite`() {
        val results = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(LeakyEngineConformance::class.java))
            .execute()

        val failed = results.testEvents().failed().list()
            .map { it.testDescriptor.displayName.removeSuffix("()") }
            .toSet()

        assertEquals(
            setOf(
                "credentials never appear in error messages",
                "read-only connection refuses a write at the server",
            ),
            failed,
            "the conformance suite did not react to the two defects the way it is supposed to",
        )
        assertTrue(
            results.testEvents().succeeded().count() > 0,
            "every case failed, which would make the suite unable to tell a broken engine from a working one",
        )
    }
}

/**
 * The broken engine's conformance run, kept out of ordinary builds by its tag.
 *
 * It is a real subclass rather than something assembled reflectively so that it stays
 * exactly as true as the two real ones: if a case is added to
 * [EngineConformanceTest], this inherits it on the same day, and the negative test
 * says so if the new case reacts to a defect it should not.
 */
@Tag("negative")
class LeakyEngineConformance : EngineConformanceTest() {

    override fun engine(): DatabaseEngine = LeakyEngine()

    override fun connectFixture(): ConnectionFixture = PostgresConformanceFixture()
}
