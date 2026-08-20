/** Locates the per-user configuration directory. */
package dev.dbide.core.appdata

import java.nio.file.Path
import java.nio.file.Paths

/** The directory name used under the platform's data location. */
const val APP_DIRECTORY_NAME = "dbide"

/** The configuration database's file name. */
const val CONFIG_DATABASE_FILE = "dbide.db"

object AppPaths {
    /**
     * The directory holding this user's configuration database:
     *
     * ```
     * macOS    ~/Library/Application Support/dbide
     * Windows  %AppData%\dbide
     * Linux    $XDG_DATA_HOME/dbide, or ~/.local/share/dbide
     * ```
     *
     * The directory is not created here; the store creates it with owner-only
     * permissions when it opens the database.
     */
    fun dataDirectory(
        os: String = System.getProperty("os.name").orEmpty(),
        env: Map<String, String> = System.getenv(),
        home: Path = Paths.get(System.getProperty("user.home")),
    ): Path = when {
        os.startsWith("Mac", ignoreCase = true) || os.contains("OS X", ignoreCase = true) ->
            home.resolve("Library").resolve("Application Support").resolve(APP_DIRECTORY_NAME)

        os.startsWith("Windows", ignoreCase = true) ->
            env["APPDATA"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?.resolve(APP_DIRECTORY_NAME)
                ?: home.resolve("AppData").resolve("Roaming").resolve(APP_DIRECTORY_NAME)

        else -> env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?.resolve(APP_DIRECTORY_NAME)
            ?: home.resolve(".local").resolve("share").resolve(APP_DIRECTORY_NAME)
    }

    /**
     * The configuration database path. A null [directory] resolves to the platform
     * default; `DBIDE_DATA_DIR` overrides it, which is how development and tests keep
     * off a developer's real installation.
     */
    fun configDatabase(
        directory: Path? = null,
        env: Map<String, String> = System.getenv(),
    ): Path {
        val base = directory
            ?: env["DBIDE_DATA_DIR"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: dataDirectory(env = env)
        return base.resolve(CONFIG_DATABASE_FILE)
    }
}
