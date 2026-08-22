package dev.caracal.engine.conformance

import dev.caracal.engine.api.DatabaseEngine
import kotlin.test.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/**
 * The suite still has every case it says it has.
 *
 * A conformance case that stopped being discovered — one that lost its `@Test`, or was
 * deleted in a merge, or was quietly renamed into something nobody runs — is
 * indistinguishable from one that passes. That is the failure `ArchitectureTest` had,
 * and no run of the suite can catch it: a run only ever sees the cases it was given,
 * and a filtered run is given fewer on purpose. [CapabilitySkips] used to try, and
 * failed any class whose cases had been narrowed by `gradle --tests` or by an IDE's
 * run-this-method.
 *
 * So it is asserted here instead, where the question is answerable: the platform is
 * asked to *discover* [DiscoveryProbe] — discover, not execute, so no server is
 * dialled and no engine is built — and the cases it finds are compared against the
 * list below by name.
 *
 * The list is written out rather than derived, and that is the whole value. A derived
 * expectation shrinks exactly when the suite does and agrees with it every time. This
 * one has to be edited, in the same commit, by whoever removes a case — which is the
 * moment a reviewer gets to ask why.
 */
class SuiteIsFullyDiscoveredTest {

    @Test
    fun `every case the suite promises is discovered`() {
        val discovered = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(DiscoveryProbe::class.java))
            .discover()
            .engineDescriptor
            .descendants
            .filter { it.isTest }
            .map { it.displayName.removeSuffix("()") }
            .toSet()

        assertEquals(
            CASES,
            discovered,
            "the conformance suite is not the list it is documented to be. A case that is " +
                "gone is a guarantee that is gone; a case that is new belongs in CASES.",
        )
    }

    private companion object {

        /** Every case an engine has to answer for, by the name its report will print. */
        val CASES = setOf(
            "the fixture matches what the engine declared",
            "connects and reports server version",
            "ping round trips",
            "failed connection names the actual failure",
            "credentials never appear in error messages",
            "credentials never appear in logs",
            "read-only connection refuses a write at the server",
            "exact numeric types survive round trip",
            "large integers do not lose precision",
            "cancel behaves per declared CancellationSupport",
            "catalog lists objects lazily",
            "notices and warnings are surfaced",
            "identifiers are quoted correctly for round trip",
            "session closes cleanly and frees threads",
            "intent classifier never returns READ_ONLY for a write",
        )
    }
}

/**
 * A subclass that exists to be discovered and never to be run.
 *
 * Discovery reads annotations and never calls either of these, so an engine and a
 * server would be two things to maintain for no assertion. The `negative` tag keeps
 * the class out of ordinary builds, where its first case would find out what `error`
 * does.
 */
@Tag("negative")
class DiscoveryProbe : EngineConformanceTest() {

    override fun engine(): DatabaseEngine = error("DiscoveryProbe is discovered, not run")

    override fun connectFixture(): ConnectionFixture = error("DiscoveryProbe is discovered, not run")
}
