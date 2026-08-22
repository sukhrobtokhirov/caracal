plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    // `api` needs it, and `api` is the right configuration for :engine-api: the SPI
    // is in the signatures :core hands out, not an implementation detail behind them.
    `java-library`
}

dependencies {
    api(project(":engine-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.slf4j.api)
    implementation(libs.sqlite.jdbc)
    implementation(libs.bouncycastle)
    implementation(libs.lettuce)

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

// The module boundary is a build rule, not a convention: a stray Compose import in
// :core would break the headless test guarantee, and nothing would notice until CI
// ran without a display.
fun classpathIds(configuration: String): Provider<List<String>> =
    configurations.named(configuration).flatMap { conf ->
        conf.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { it.id.componentIdentifier.displayName }
        }
    }

val assertNoComposeDependency = tasks.register("assertNoComposeDependency") {
    val runtimeIds = classpathIds("runtimeClasspath")
    val testIds = classpathIds("testRuntimeClasspath")
    doLast {
        val offenders = (runtimeIds.get() + testIds.get()).filter {
            it.startsWith("org.jetbrains.compose") ||
                it.startsWith("androidx.compose") ||
                it.startsWith("org.jetbrains.skiko")
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                ":core must not depend on Compose. Offending artifacts: ${offenders.distinct()}",
            )
        }
    }
}

tasks.named("check") { dependsOn(assertNoComposeDependency) }

tasks.withType<Test>().configureEach {
    // Integration tests are opt-in; the flag has to survive the jump into the test JVM.
    val integration = providers.environmentVariable("CARACAL_INTEGRATION").getOrElse("")
    environment("CARACAL_INTEGRATION", integration)
    // Gradle does not track a task's environment as an input — deliberately, so that
    // an unrelated PATH change does not invalidate every task. That leaves a trap
    // here: with the results of a previous run in place, `CARACAL_INTEGRATION=1
    // ./gradlew :core:test` reports UP-TO-DATE and the Testcontainers suites quietly
    // do not run. Declaring the flag makes flipping it re-run the tests.
    inputs.property("caracalIntegration", integration)
    // :core must pass with no display server. Running headless makes that fail here
    // rather than on a CI runner that has no screen.
    systemProperty("java.awt.headless", "true")
}

/**
 * The Phase 0 coverage floor.
 *
 * Everything from here to the end of the multi-engine work is a refactor, and a
 * refactor is exactly when a characterization test gets deleted along with the code
 * it was pinning down. The classes named below are the ones whose sharp edges the
 * product is built on — error position mapping, numeric precision, statement
 * classification, the safety decision table, formula neutralization — so each is
 * held individually rather than letting a high average hide one that lost its tests.
 *
 * Written out rather than expressed through `koverVerify` because Kover's rule scope
 * takes no class filter, so a rule can only bound the module as a whole. It also
 * buys the two things that matter here: the failure names the class that slipped,
 * and a class that has *disappeared* from the report is a failure too. The second is
 * the one that earns its keep — Phase 1 moves these files into new modules, and a
 * check that quietly passes because the class it was watching is no longer there is
 * worse than no check.
 *
 * Measured with the Testcontainers suites off, which is how an ordinary `check` and
 * a pull-request CI run measure it. Coverage that only exists when a container is
 * available is not there when it is needed.
 */
val characterizedClasses = listOf(
    "dev.caracal.core.postgres.PostgresErrors",
    "dev.caracal.core.postgres.PostgresValues",
    "dev.caracal.core.sql.Statement",
    "dev.caracal.core.sql.StatementSplitter",
    "dev.caracal.core.sql.StatementClassifier",
    "dev.caracal.core.redis.RedisCommandGuard",
    "dev.caracal.core.policy.DataSafetyPolicy",
    "dev.caracal.core.export.CsvWriter",
)

val characterizationFloor = 85

val assertCharacterizationCoverage = tasks.register("assertCharacterizationCoverage") {
    dependsOn(tasks.named("koverXmlReport"))
    val report = layout.buildDirectory.file("reports/kover/report.xml")
    val expected = characterizedClasses
    val floor = characterizationFloor
    inputs.file(report)
    inputs.property("classes", expected)
    inputs.property("floor", floor)
    outputs.upToDateWhen { true }

    doLast {
        val file = report.get().asFile
        if (!file.isFile) throw GradleException("No Kover report at $file; run :core:koverXmlReport first.")

        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .also { it.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            .newDocumentBuilder()
            .parse(file)

        // Kover writes JaCoCo's schema: package names use slashes, and every class
        // carries a LINE counter of missed and covered.
        val measured = mutableMapOf<String, Pair<Int, Int>>()
        val classes = document.getElementsByTagName("class")
        for (index in 0 until classes.length) {
            val element = classes.item(index) as org.w3c.dom.Element
            val name = element.getAttribute("name").replace('/', '.')
            val counters = element.getElementsByTagName("counter")
            for (counterIndex in 0 until counters.length) {
                val counter = counters.item(counterIndex) as org.w3c.dom.Element
                if (counter.parentNode !== element || counter.getAttribute("type") != "LINE") continue
                measured[name] = counter.getAttribute("missed").toInt() to counter.getAttribute("covered").toInt()
            }
        }

        val missing = expected.filterNot { measured.containsKey(it) }
        val short = expected.mapNotNull { name ->
            val (missedLines, coveredLines) = measured[name] ?: return@mapNotNull null
            val total = missedLines + coveredLines
            val percent = if (total == 0) 0 else coveredLines * 100 / total
            if (percent < floor) "  $name: $percent% ($coveredLines of $total lines)" else null
        }

        if (missing.isNotEmpty() || short.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The Phase 0 characterization floor of $floor% line coverage is not met.")
                    if (short.isNotEmpty()) {
                        appendLine("Below the floor:")
                        short.forEach { appendLine(it) }
                    }
                    if (missing.isNotEmpty()) {
                        appendLine("Not in the coverage report at all — moved, renamed, or removed:")
                        missing.forEach { appendLine("  $it") }
                        appendLine(
                            "If that move was deliberate, update `characterizedClasses` in " +
                                "core/build.gradle.kts in the same commit, so the class keeps its floor " +
                                "at its new name.",
                        )
                    }
                },
            )
        }
    }
}

tasks.named("check") { dependsOn(assertCharacterizationCoverage) }
