package dev.caracal.engine.api

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The only logic in the SPI, and it is here because every engine's version string is
 * a different shape and the dashboard shows all of them.
 */
class ServerVersionTest {

    @Test
    fun `PostgreSQL's two-part version parses`() {
        val version = ServerVersion.parse("17.2")

        assertEquals("17.2", version.raw)
        assertEquals(17, version.major)
        assertEquals(2, version.minor)
    }

    @Test
    fun `Redis's three-part version keeps only the first two numbers`() {
        val version = ServerVersion.parse("7.2.4")

        assertEquals(7, version.major)
        assertEquals(2, version.minor)
    }

    @Test
    fun `a vendor suffix does not stop the numbers being read`() {
        val version = ServerVersion.parse("11.4.3-MariaDB-ubu2404")

        assertEquals(11, version.major)
        assertEquals(4, version.minor)
        assertEquals("11.4.3-MariaDB-ubu2404", version.raw)
    }

    @Test
    fun `leading whitespace is not a parse failure`() {
        assertEquals(15, ServerVersion.parse("  15.6").major)
    }

    @Test
    fun `a version that starts with a word keeps its text and reports no numbers`() {
        val version = ServerVersion.parse("PostgreSQL 17.2 on aarch64-apple-darwin")

        assertEquals("PostgreSQL 17.2 on aarch64-apple-darwin", version.raw)
        assertNull(version.major)
        assertNull(version.minor)
    }

    @Test
    fun `a server that would not say is unknown rather than empty`() {
        assertEquals(ServerVersion.UNKNOWN, ServerVersion.parse(null))
        assertEquals(ServerVersion.UNKNOWN, ServerVersion.parse("   "))
    }

    @Test
    fun `a one-part version has a major and no minor`() {
        val version = ServerVersion.parse("8")

        assertEquals(8, version.major)
        assertNull(version.minor)
    }

    @Test
    fun `session-affecting statements do not count as modifications`() {
        // The rule DataSafetyPolicy already applies to SQL: a `SET` runs in its own
        // transaction and is gone before the next statement, so refusing it would be
        // refusing something already harmless. Everything else here does count,
        // UNKNOWN loudest of all.
        assertEquals(
            listOf(WriteIntent.READ_ONLY, WriteIntent.CONNECTION_AFFECTING),
            WriteIntent.entries.filterNot { it.modifies },
        )
    }
}
