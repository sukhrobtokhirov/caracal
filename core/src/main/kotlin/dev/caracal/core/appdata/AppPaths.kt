/** Locates the per-user configuration directory. */
package dev.caracal.core.appdata

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** The directory name used under the platform's data location. */
const val APP_DIRECTORY_NAME = "caracal"

/** The configuration database's file name. */
const val CONFIG_DATABASE_FILE = "caracal.db"

/**
 * What the application called itself before M5 named it Caracal.
 *
 * Nothing was ever released under that name, so the only installations that carry
 * it are the ones built from this repository — but those hold a real vault, and a
 * rename that walked past it would present their owner with an empty application
 * and no way to see that their connections still exist.
 */
private const val LEGACY_APP_DIRECTORY_NAME = "dbide"
private const val LEGACY_CONFIG_DATABASE_FILE = "dbide.db"

object AppPaths {
    /**
     * The directory holding this user's configuration database:
     *
     * ```
     * macOS    ~/Library/Application Support/caracal
     * Windows  %AppData%\caracal
     * Linux    $XDG_DATA_HOME/caracal, or ~/.local/share/caracal
     * ```
     *
     * The directory is not created here; the store creates it with owner-only
     * permissions when it opens the database.
     */
    fun dataDirectory(
        os: String = System.getProperty("os.name").orEmpty(),
        env: Map<String, String> = System.getenv(),
        home: Path = Paths.get(System.getProperty("user.home")),
        name: String = APP_DIRECTORY_NAME,
    ): Path = when {
        os.startsWith("Mac", ignoreCase = true) || os.contains("OS X", ignoreCase = true) ->
            home.resolve("Library").resolve("Application Support").resolve(name)

        os.startsWith("Windows", ignoreCase = true) ->
            env["APPDATA"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?.resolve(name)
                ?: home.resolve("AppData").resolve("Roaming").resolve(name)

        else -> env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?.resolve(name)
            ?: home.resolve(".local").resolve("share").resolve(name)
    }

    /**
     * The configuration database path. A null [directory] resolves to the platform
     * default; `CARACAL_DATA_DIR` overrides it, which is how development and tests keep
     * off a developer's real installation.
     */
    fun configDatabase(
        directory: Path? = null,
        env: Map<String, String> = System.getenv(),
    ): Path {
        val base = directory
            ?: env["CARACAL_DATA_DIR"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: dataDirectory(env = env)
        return base.resolve(CONFIG_DATABASE_FILE)
    }

    /**
     * Moves a pre-rename installation to its new name, once, and reports where it
     * came from.
     *
     * The move is a rename within one parent directory, so it is atomic and cheap,
     * and it carries the WAL and shared-memory files along with the database rather
     * than leaving a torn write behind. The database files are renamed by prefix
     * first, for the same reason: `caracal.db` beside a `dbide.db-wal` would be a
     * database missing its most recent transactions. The directory rename goes last
     * so that the destination existing means the adoption finished, which is what
     * the skip below assumes.
     *
     * It does nothing at all when the destination already exists — two installations
     * are a situation to leave alone, not to merge — and nothing when the data
     * directory was named explicitly, where the caller has already said which one
     * they mean. A failure is raised rather than swallowed: starting up with an empty
     * vault would look exactly like the data being gone.
     */
    fun adoptLegacyData(
        os: String = System.getProperty("os.name").orEmpty(),
        env: Map<String, String> = System.getenv(),
        home: Path = Paths.get(System.getProperty("user.home")),
    ): Path? {
        if (!env["CARACAL_DATA_DIR"].isNullOrBlank()) return null

        val destination = dataDirectory(os, env, home)
        val legacy = dataDirectory(os, env, home, name = LEGACY_APP_DIRECTORY_NAME)
        if (!Files.isDirectory(legacy) || Files.exists(destination)) return null

        // The files are renamed first, inside the directory that is still the legacy
        // one, and the directory is renamed last. The order matters because the
        // directory rename is what the guard above tests: doing it first left a
        // window where a crash produced a `caracal` directory holding `dbide.db`,
        // and the next launch skipped adoption, opened a new empty `caracal.db`
        // beside it, and offered the first-run screen to someone whose vault was
        // sitting untouched in the same folder.
        legacy.listDirectoryEntries()
            .filter { it.name.startsWith(LEGACY_CONFIG_DATABASE_FILE) }
            .forEach { file ->
                val suffix = file.name.removePrefix(LEGACY_CONFIG_DATABASE_FILE)
                Files.move(file, legacy.resolve(CONFIG_DATABASE_FILE + suffix))
            }
        Files.move(legacy, destination)
        return legacy
    }
}
