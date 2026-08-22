package dev.caracal.app

import java.io.File
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The single most important structural invariant of the multi-engine work: **the UI
 * must not compile against a concrete engine.**
 *
 * It is enforced mechanically rather than by discipline, because discipline is what
 * fails on the busy afternoon when a view model needs one field off a
 * `RedisSession` and the import is right there. Once the UI holds a concrete engine
 * type, adding the fourth engine means editing the UI, and the module boundary is
 * decoration.
 *
 * **It passes as of Phase 2**, which is what Phase 2 was for. Thirteen files under
 * `app/src/main` used to name `dev.caracal.core.redis` directly, holding a paged
 * value, a key, or a reply tree with no indirection at all; the vocabulary they hold
 * is in `:engine-api` now and reaches them through a facet. It was `@Disabled` for
 * exactly one phase, and the last act of Phase 2 was deleting that line and watching
 * this go green.
 *
 * Two things about the forbidden list are deliberate.
 *
 * The `dev.` prefix is on every entry, because the root package is `dev.caracal` and
 * a list written as `caracal.engine.postgres` matches nothing at all — a test that
 * passes by finding no violations of a rule it is not checking.
 *
 * Both the current and the eventual package names are listed. The engines live in
 * `dev.caracal.core.postgres` today and will live in `dev.caracal.engine.postgres`
 * after the module split. A list containing only the second would pass now for the
 * same vacuous reason, and would go on passing right through the phase it exists to
 * hold to account.
 *
 * `:engine-api`'s equivalent is a build rule rather than a test:
 * `assertSpiHasNoDependencies` fails the SPI module's `check` if anything but Kotlin
 * and coroutines reaches its runtime classpath, which is strictly stronger than
 * scanning its imports — an engine type it could not compile against cannot be
 * imported at all.
 */
class ArchitectureTest {

    @Test
    fun `the UI does not depend on engine implementations`() {
        val violations = sourceFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.trimStart()
                // Any mention in code, not only an `import` line. A view model that
                // writes `dev.caracal.core.redis.RedisSession` in full depends on it
                // exactly as much as one that imports it, and would walk past a check
                // that only reads the import block. Comment lines are excused because
                // a KDoc link naming a type is not a dependency on it — one of those
                // is the difference between fourteen files mentioning these packages
                // and thirteen actually using them.
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                    return@mapIndexedNotNull null
                }
                FORBIDDEN.firstOrNull { code.contains(it) }?.let { "${file.path}:${index + 1}: $it" }
            }
        }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("The UI imports engine internals, so it cannot be compiled without them:")
                violations.forEach { appendLine("  $it") }
            },
        )
    }

    @Test
    fun `the scan actually reads the UI's sources`() {
        // The guard on the guard. A disabled test that would also have passed against
        // an empty file list is worth nothing, and a moved source root or a changed
        // working directory would produce exactly that — silently.
        val files = sourceFiles()

        assertTrue(files.isNotEmpty(), "no Kotlin sources found under $SOURCE_ROOT")
        assertTrue(
            files.any { it.name == "Main.kt" },
            "found ${files.size} files under $SOURCE_ROOT but not Main.kt, so this is the wrong tree",
        )
    }

    private fun sourceFiles(): List<File> =
        File(SOURCE_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private companion object {
        /** Relative to the module directory, which is a Gradle test's working directory. */
        const val SOURCE_ROOT = "src/main/kotlin"

        val FORBIDDEN = listOf(
            // Where the engines are now.
            "dev.caracal.core.postgres",
            "dev.caracal.core.redis",
            // Where they are going.
            "dev.caracal.engine.postgres",
            "dev.caracal.engine.redis",
            "dev.caracal.engine.mysql",
            "dev.caracal.engine.sqlite",
        )
    }
}
