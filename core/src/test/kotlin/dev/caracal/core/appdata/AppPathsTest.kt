package dev.caracal.core.appdata

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AppPathsTest {
    private val home = Paths.get("/home/user")

    @Test
    fun `macOS uses Application Support`() {
        val directory = AppPaths.dataDirectory(os = "Mac OS X", env = emptyMap(), home = home)

        assertEquals(Paths.get("/home/user/Library/Application Support/caracal"), directory)
    }

    @Test
    fun `Windows uses APPDATA when it is set`() {
        val directory = AppPaths.dataDirectory(
            os = "Windows 11",
            env = mapOf("APPDATA" to "/Users/user/AppData/Roaming"),
            home = home,
        )

        assertEquals(Paths.get("/Users/user/AppData/Roaming/caracal"), directory)
    }

    @Test
    fun `Windows falls back to the home directory when APPDATA is missing`() {
        val directory = AppPaths.dataDirectory(os = "Windows 11", env = emptyMap(), home = home)

        assertEquals(Paths.get("/home/user/AppData/Roaming/caracal"), directory)
    }

    @Test
    fun `Linux prefers XDG_DATA_HOME and otherwise uses the default share directory`() {
        assertEquals(
            Paths.get("/xdg/caracal"),
            AppPaths.dataDirectory(os = "Linux", env = mapOf("XDG_DATA_HOME" to "/xdg"), home = home),
        )
        assertEquals(
            Paths.get("/home/user/.local/share/caracal"),
            AppPaths.dataDirectory(os = "Linux", env = emptyMap(), home = home),
        )
    }

    @Test
    fun `a blank environment value is ignored rather than producing a relative path`() {
        assertEquals(
            Paths.get("/home/user/.local/share/caracal"),
            AppPaths.dataDirectory(os = "Linux", env = mapOf("XDG_DATA_HOME" to "  "), home = home),
        )
    }

    @Test
    fun `the database file lives inside the data directory`() {
        assertEquals(Paths.get("/data/caracal.db"), AppPaths.configDatabase(directory = Paths.get("/data")))
    }

    @Test
    fun `CARACAL_DATA_DIR overrides the platform default, so tests stay off a real installation`() {
        assertEquals(
            Paths.get("/tmp/caracal-test/caracal.db"),
            AppPaths.configDatabase(env = mapOf("CARACAL_DATA_DIR" to "/tmp/caracal-test")),
        )
        assertTrue(AppPaths.configDatabase(env = emptyMap()).isAbsolute)
    }
}

class LegacyDataTest {
    @TempDir
    lateinit var home: Path

    private val linux = "Linux"

    /** The pre-rename installation, with the WAL files a live SQLite database has. */
    private fun legacyInstallation(): Path {
        val legacy = home.resolve(".local").resolve("share").resolve("dbide")
        Files.createDirectories(legacy)
        legacy.resolve("dbide.db").writeText("vault")
        legacy.resolve("dbide.db-wal").writeText("recent transactions")
        legacy.resolve("dbide.db-shm").writeText("shared memory")
        return legacy
    }

    private fun destination(): Path = home.resolve(".local").resolve("share").resolve("caracal")

    @Test
    fun `a pre-rename installation is moved, database and write-ahead log together`() {
        val legacy = legacyInstallation()

        val moved = AppPaths.adoptLegacyData(os = linux, env = emptyMap(), home = home)

        assertEquals(legacy, moved)
        assertFalse(Files.exists(legacy), "the old directory is gone rather than duplicated")
        assertEquals("vault", destination().resolve("caracal.db").readText())
        // The tail of the database. Left under its old name it would be invisible to
        // SQLite, which is to say silently discarded.
        assertEquals("recent transactions", destination().resolve("caracal.db-wal").readText())
        assertEquals("shared memory", destination().resolve("caracal.db-shm").readText())
    }

    @Test
    fun `an installation that is already Caracal is left alone`() {
        legacyInstallation()
        Files.createDirectories(destination())
        destination().resolve("caracal.db").writeText("the current vault")

        assertNull(AppPaths.adoptLegacyData(os = linux, env = emptyMap(), home = home))

        assertEquals("the current vault", destination().resolve("caracal.db").readText())
        assertTrue(Files.exists(home.resolve(".local/share/dbide/dbide.db")), "and the old one is not deleted")
    }

    @Test
    fun `an explicit data directory is never migrated into`() {
        legacyInstallation()

        val moved = AppPaths.adoptLegacyData(
            os = linux,
            env = mapOf("CARACAL_DATA_DIR" to home.resolve("scratch").toString()),
            home = home,
        )

        assertNull(moved)
        assertFalse(Files.exists(destination()))
    }

    @Test
    fun `a first run with nothing to migrate does nothing`() {
        assertNull(AppPaths.adoptLegacyData(os = linux, env = emptyMap(), home = home))
        assertFalse(Files.exists(destination()))
    }
}
