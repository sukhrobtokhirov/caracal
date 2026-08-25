import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
    runtimeOnly(project(":engine-postgres"))
    runtimeOnly(project(":engine-redis"))

    testImplementation(libs.compose.ui.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * A git command's output, or null where there is no answer — an unpacked source
 * archive, a machine without git, a repository with no commits yet. Every caller
 * has a defensible fallback, because a build that fails for want of version
 * decoration is a build that fails for nothing.
 */
fun git(vararg arguments: String): String? = try {
    val process = ProcessBuilder(listOf("git") + arguments)
        .directory(rootDir)
        .redirectErrorStream(false)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.errorStream.close()
    if (process.waitFor() == 0 && output.isNotEmpty()) output else null
} catch (_: Exception) {
    null
}

/**
 * Version, commit, and date, written where the running application can read them
 * without a build system present.
 *
 * "Built" is the commit's own timestamp rather than the moment the compiler ran, so
 * that two builds of the same commit describe themselves identically — the same
 * reason the checksums in a release are worth publishing. A working tree with
 * uncommitted changes says so in the commit field, because a build that is not the
 * commit it names is exactly the build a bug report needs to distinguish.
 */
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val version = project.version.toString()
    val head = git("rev-parse", "--short=7", "HEAD")
    // `git status --porcelain` prints nothing for a clean tree, which [git] reports
    // as null along with every other kind of no-answer. A machine without git
    // therefore looks clean here, and correctly so: its commit is "unknown" already.
    val dirty = git("status", "--porcelain") != null
    val commit = head?.let { if (dirty) "$it-dirty" else it } ?: "unknown"
    val date = git("log", "-1", "--format=%cI").orEmpty()
    val output = layout.buildDirectory.dir("generated/buildInfo")

    inputs.property("version", version)
    inputs.property("commit", commit)
    inputs.property("date", date)
    outputs.dir(output)

    doLast {
        output.get().file("caracal-build.properties").asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                |version=$version
                |commit=$commit
                |date=$date
                |
                """.trimMargin(),
            )
        }
    }
}

sourceSets.main { resources.srcDir(generateBuildInfo) }

// macOS codesign refuses to sign an app image carrying a com.apple.FinderInfo xattr,
// which iCloud Drive attaches to everything it syncs. When the checkout lives in a
// synced folder, point the packaging output somewhere local:
//   ./gradlew :app:packageDistributionForCurrentOS -Pcaracal.distributionsDir=/tmp/caracal
val distributionsDir = providers.gradleProperty("caracal.distributionsDir")
    .orElse(providers.environmentVariable("CARACAL_DISTRIBUTIONS_DIR"))

compose.desktop {
    application {
        mainClass = "dev.caracal.app.MainKt"

        // Skiko loads its native library directly; without this JDK 25 prints a
        // restricted-method warning on every launch and will block the call later.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            outputBaseDir.set(
                layout.dir(distributionsDir.map { File(it) })
                    .orElse(layout.buildDirectory.dir("compose/binaries")),
            )
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // One mark, three formats, none of them hand-edited: `app/icons` is
            // rendered from `branding/caracal-source.png` by `branding/render-icons.py`.
            // Without these, jpackage ships the default Java icon and the
            // application arrives in the dock, the Start menu, and the .desktop
            // entry looking like nobody's.
            packageName = "Caracal"
            packageVersion = project.version.toString()
            vendor = "Caracal"
            description = "A desktop IDE for PostgreSQL and Redis"
            copyright = "Copyright 2026 the Caracal authors. Apache-2.0."
            // Shown by the Windows installer and written into the Debian package,
            // which is where someone installing an unsigned binary looks to find
            // out what they are agreeing to.
            licenseFile.set(rootProject.file("LICENSE"))

            // jlink keeps only these platform modules. java.sql and java.naming are
            // pgjdbc's; java.instrument, java.security.jgss, and jdk.jfr arrived with
            // Netty under Lettuce; jdk.crypto.ec is TLS, which is loaded reflectively
            // and so never appears in :app:suggestRuntimeModules. Re-run that task
            // after adding a dependency rather than guessing.
            modules(
                "java.instrument",
                "java.management",
                "java.naming",
                "java.security.jgss",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.jfr",
                "jdk.unsupported",
            )

            macOS {
                bundleID = "dev.caracal.app"
                iconFile.set(project.file("icons/caracal.icns"))
                dockName = "Caracal"
                // macOS rejects an app version whose first number is zero, so a 0.x
                // release shows 1.0.0 as its marketing version while the build
                // version carries the real one. Finder is then the one place that
                // disagrees with `--version`, About, and the installer's file name;
                // the arithmetic disappears at 1.0.0, which is the first release
                // where it can.
                packageVersion = project.version.toString()
                    .takeUnless { it.startsWith("0.") } ?: "1.0.0"
                packageBuildVersion = project.version.toString()
            }
            windows {
                iconFile.set(project.file("icons/caracal.ico"))
                menu = true
                upgradeUuid = "6f2e3a54-9d4f-4f3f-9d0b-0a1f3b6c8e21"
            }
            linux {
                iconFile.set(project.file("icons/caracal.png"))
                packageName = "caracal"
            }
        }
    }
}

// The generated resource is what About and `--version` read, so a test asserts it
// carries the version Gradle was told to build — that wiring is invisible right up
// until a release names the wrong number.
tasks.test {
    systemProperty("caracal.expectedVersion", project.version.toString())
}

// Development runs can point at a scratch configuration directory instead of the
// real one under ~/Library/Application Support:
//   CARACAL_DATA_DIR=/tmp/caracal ./gradlew :app:run
tasks.withType<JavaExec>().configureEach {
    listOf("CARACAL_DATA_DIR", "CARACAL_LOG_STARTUP").forEach { key ->
        environment(key, providers.environmentVariable(key).getOrElse(""))
    }
}
