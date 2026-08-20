package dev.dbide.core.appdata

import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AppPathsTest {
    private val home = Paths.get("/home/user")

    @Test
    fun `macOS uses Application Support`() {
        val directory = AppPaths.dataDirectory(os = "Mac OS X", env = emptyMap(), home = home)

        assertEquals(Paths.get("/home/user/Library/Application Support/dbide"), directory)
    }

    @Test
    fun `Windows uses APPDATA when it is set`() {
        val directory = AppPaths.dataDirectory(
            os = "Windows 11",
            env = mapOf("APPDATA" to "/Users/user/AppData/Roaming"),
            home = home,
        )

        assertEquals(Paths.get("/Users/user/AppData/Roaming/dbide"), directory)
    }

    @Test
    fun `Windows falls back to the home directory when APPDATA is missing`() {
        val directory = AppPaths.dataDirectory(os = "Windows 11", env = emptyMap(), home = home)

        assertEquals(Paths.get("/home/user/AppData/Roaming/dbide"), directory)
    }

    @Test
    fun `Linux prefers XDG_DATA_HOME and otherwise uses the default share directory`() {
        assertEquals(
            Paths.get("/xdg/dbide"),
            AppPaths.dataDirectory(os = "Linux", env = mapOf("XDG_DATA_HOME" to "/xdg"), home = home),
        )
        assertEquals(
            Paths.get("/home/user/.local/share/dbide"),
            AppPaths.dataDirectory(os = "Linux", env = emptyMap(), home = home),
        )
    }

    @Test
    fun `a blank environment value is ignored rather than producing a relative path`() {
        assertEquals(
            Paths.get("/home/user/.local/share/dbide"),
            AppPaths.dataDirectory(os = "Linux", env = mapOf("XDG_DATA_HOME" to "  "), home = home),
        )
    }

    @Test
    fun `the database file lives inside the data directory`() {
        assertEquals(Paths.get("/data/dbide.db"), AppPaths.configDatabase(directory = Paths.get("/data")))
    }

    @Test
    fun `DBIDE_DATA_DIR overrides the platform default, so tests stay off a real installation`() {
        assertEquals(
            Paths.get("/tmp/dbide-test/dbide.db"),
            AppPaths.configDatabase(env = mapOf("DBIDE_DATA_DIR" to "/tmp/dbide-test")),
        )
        assertTrue(AppPaths.configDatabase(env = emptyMap()).isAbsolute)
    }
}
