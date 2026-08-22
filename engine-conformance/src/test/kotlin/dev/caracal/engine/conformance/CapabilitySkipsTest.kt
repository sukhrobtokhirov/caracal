package dev.caracal.engine.conformance

import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/**
 * The guard that stops the conformance suite passing for the wrong reason, checked the
 * only way a guard can be: by running things it is supposed to reject.
 *
 * Every probe below is a two-method class that would report success in an ordinary
 * build — a skip prints as a skip, and nobody counts them. That is exactly the failure
 * `ArchitectureTest` had, and the reason [CapabilitySkips] exists at all, so the
 * extension is held to the same standard it holds the suite to.
 *
 * The probes carry the `negative` tag, which the root build excludes from discovery.
 * Without it Gradle would find them itself and report a build full of deliberate
 * failures.
 */
class CapabilitySkipsTest {

    @Test
    fun `a case that skips for a declared reason is allowed`() {
        assertNull(complaintFrom(HonestProbe::class.java), "an honest skip was reported as a problem")
    }

    @Test
    fun `a skip with no capability behind it fails the class`() {
        val complaint = complaintFrom(UnexplainedSkipProbe::class.java)

        assertTrue(
            complaint?.contains("without naming a capability") == true,
            "a bare assumption passed as a capability skip: $complaint",
        )
    }

    @Test
    fun `a case every engine must pass may not skip itself`() {
        val complaint = complaintFrom(UngatedSkipProbe::class.java)

        assertTrue(
            complaint?.contains("not @CapabilityGated") == true,
            "an ungated case excused itself and nothing noticed: $complaint",
        )
    }

    @Test
    fun `a disabled case fails the class rather than quietly not running`() {
        val complaint = complaintFrom(DisabledCaseProbe::class.java)

        assertTrue(complaint?.contains("is disabled") == true, "a switched-off guarantee went unreported: $complaint")
    }

    @Test
    fun `an engine excused from every case has proved nothing`() {
        val complaint = complaintFrom(EverythingSkippedProbe::class.java)

        assertTrue(complaint?.contains("Every case skipped") == true, "a wholly skipped run passed: $complaint")
    }

    /** What the class-level check said went wrong, or null if it was happy. */
    private fun complaintFrom(probe: Class<*>): String? = EngineTestKit.engine("junit-jupiter")
        .selectors(selectClass(probe))
        .execute()
        .containerEvents()
        .failed()
        .list()
        .firstNotNullOfOrNull { event ->
            event.getPayload(TestExecutionResult::class.java).orElse(null)
                ?.throwable?.orElse(null)?.message
        }
}

@Tag("negative")
@ExtendWith(CapabilitySkips::class)
class HonestProbe {

    @Test
    fun `runs`() = Unit

    @Test
    @CapabilityGated
    fun `skips because the engine says so`() = requireCapability(false, "family is KEY_VALUE")
}

@Tag("negative")
@ExtendWith(CapabilitySkips::class)
class UnexplainedSkipProbe {

    @Test
    fun `runs`() = Unit

    @Test
    @CapabilityGated
    fun `skips on a hunch`() = assumeTrue(false)
}

@Tag("negative")
@ExtendWith(CapabilitySkips::class)
class UngatedSkipProbe {

    @Test
    fun `runs`() = Unit

    @Test
    fun `excuses itself from a universal case`() = requireCapability(false, "family is KEY_VALUE")
}

@Tag("negative")
@ExtendWith(CapabilitySkips::class)
class DisabledCaseProbe {

    @Test
    fun `runs`() = Unit

    @Test
    @Disabled("switched off while somebody looks into it")
    fun `never runs again`() = Unit
}

@Tag("negative")
@ExtendWith(CapabilitySkips::class)
class EverythingSkippedProbe {

    @Test
    @CapabilityGated
    fun `skips`() = requireCapability(false, "family is KEY_VALUE")

    @Test
    @CapabilityGated
    fun `skips too`() = requireCapability(false, "identifierQuote is NONE")
}
