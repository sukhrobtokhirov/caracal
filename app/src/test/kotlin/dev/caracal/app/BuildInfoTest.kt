package dev.caracal.app

import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BuildInfoTest {
    private fun read(text: String) = BuildInfo.read(text.trimIndent().byteInputStream())

    @Test
    fun `a stamped build describes itself in one line`() {
        val info = read(
            """
            version=0.1.0
            commit=1a2b3c4
            date=2026-08-21T22:46:03+05:00
            """,
        )

        assertEquals("Caracal 0.1.0 (commit 1a2b3c4, built 2026-08-21T22:46:03+05:00)", info.describe())
    }

    @Test
    fun `a build from outside a checkout says so rather than inventing a commit`() {
        val info = read(
            """
            version=0.1.0
            commit=unknown
            date=
            """,
        )

        assertNull(info.builtAt)
        assertEquals("Caracal 0.1.0 (commit unknown)", info.describe())
    }

    @Test
    fun `a date that cannot be parsed is absent, not fatal`() {
        // This runs before the window opens. A malformed field must cost the About
        // panel one line, not cost the user their application.
        val info = read(
            """
            version=0.1.0
            commit=1a2b3c4
            date=last Tuesday
            """,
        )

        assertNull(info.builtAt)
        assertEquals("Caracal 0.1.0 (commit 1a2b3c4)", info.describe())
    }

    @Test
    fun `an empty resource falls back to the development identity`() {
        assertEquals(BuildInfo.UNKNOWN, read(""))
    }

    @Test
    fun `the resource on the classpath carries the version Gradle was told to build`() {
        // The generation is a build-script task and the reader is application code;
        // nothing else connects them. Without this, a release could ship an installer
        // named 0.2.0 whose About panel says 0.1.0 and no test would notice.
        val expected = System.getProperty("caracal.expectedVersion")
        assertTrue(!expected.isNullOrBlank(), "the test task must pass the project version")
        assertEquals(expected, BuildInfo.current.version)
    }

    @Test
    fun `this build knows which commit it came from`() {
        // Belt and braces on the same wiring: run from a checkout, the resource has a
        // commit in it, and its date is not in some other decade.
        val info = BuildInfo.current
        if (info.commit != "unknown") {
            assertTrue(info.commit.length >= 7, "a short hash, optionally marked dirty: ${info.commit}")
            assertTrue(
                info.builtAt!!.isAfter(OffsetDateTime.parse("2026-01-01T00:00:00Z")),
                "the commit date should be this project's, not the epoch: ${info.builtAt}",
            )
        }
    }
}
