import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.compose.ui.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// macOS codesign refuses to sign an app image carrying a com.apple.FinderInfo xattr,
// which iCloud Drive attaches to everything it syncs. When the checkout lives in a
// synced folder, point the packaging output somewhere local:
//   ./gradlew :app:packageDistributionForCurrentOS -Pdbide.distributionsDir=/tmp/dbide
val distributionsDir = providers.gradleProperty("dbide.distributionsDir")
    .orElse(providers.environmentVariable("DBIDE_DISTRIBUTIONS_DIR"))

compose.desktop {
    application {
        mainClass = "dev.dbide.app.MainKt"

        // Skiko loads its native library directly; without this JDK 25 prints a
        // restricted-method warning on every launch and will block the call later.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            outputBaseDir.set(
                layout.dir(distributionsDir.map { File(it) })
                    .orElse(layout.buildDirectory.dir("compose/binaries")),
            )
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Database IDE"
            packageVersion = "0.1.0"
            vendor = "Database IDE"
            description = "A desktop IDE for PostgreSQL and Redis"

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
                bundleID = "dev.dbide.app"
                dockName = "Database IDE"
                // macOS rejects an app version whose first number is zero, so the
                // marketing version starts at 1.0.0 there while the build version
                // carries the real one. Revisit when M5 sets the release version.
                packageVersion = "1.0.0"
                packageBuildVersion = "0.1.0"
            }
            windows {
                menu = true
                upgradeUuid = "6f2e3a54-9d4f-4f3f-9d0b-0a1f3b6c8e21"
            }
            linux {
                packageName = "database-ide"
            }
        }
    }
}

// Development runs can point at a scratch configuration directory instead of the
// real one under ~/Library/Application Support:
//   DBIDE_DATA_DIR=/tmp/dbide ./gradlew :app:run
tasks.withType<JavaExec>().configureEach {
    listOf("DBIDE_DATA_DIR", "DBIDE_LOG_STARTUP").forEach { key ->
        environment(key, providers.environmentVariable(key).getOrElse(""))
    }
}
