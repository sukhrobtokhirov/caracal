plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

/**
 * The tests every engine has to pass, as a dependency rather than as a file to copy.
 *
 * Section 10's whole claim is that five engines do not become five rotting codebases,
 * because every one of them runs the same tests. A suite that lived in `:core`'s test
 * sources would stop being reachable the moment an engine moved into its own module —
 * which is the very next issue — and a suite that was copied would be four suites
 * within a release. So it is a module, on the test classpath of whoever has an engine
 * to prove, including an engine written outside this repository.
 *
 * A `main` source set and not a test one, deliberately: the consumers are other
 * modules' tests, and a test source set is not something another project can depend
 * on without `java-test-fixtures` ceremony that buys nothing here. JUnit is `api` for
 * the same reason — a subclass inherits `@Test` methods and has to see the
 * annotations that declare them.
 *
 * What it may depend on is the short list `:engine-api` already allows, plus a test
 * framework. A driver or Testcontainers here would be this module deciding how an
 * engine is *fixtured*, and the fixture is precisely the half that belongs to the
 * engine.
 */
dependencies {
    api(project(":engine-api"))
    api(libs.junit.jupiter)
    api(kotlin("test-junit5"))
    api(libs.kotlinx.coroutines.core)

    // The extension that decides what counts as an honest skip is the one piece of
    // real logic here, so it has tests of its own. They run probe classes through the
    // platform and read back what the check said.
    testImplementation(libs.junit.platform.testkit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val allowedPrefixes = listOf(
    "org.jetbrains.kotlin",
    "org.jetbrains.kotlinx:kotlinx-coroutines",
    "org.jetbrains:annotations",
    "org.junit",
    "org.opentest4j",
    "org.apiguardian",
    "project ':engine-api'",
)

val assertConformanceStaysPortable = tasks.register("assertConformanceStaysPortable") {
    val runtimeIds = configurations.named("runtimeClasspath").flatMap { conf ->
        conf.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { it.id.componentIdentifier.displayName }
        }
    }
    inputs.files(configurations.named("runtimeClasspath"))
    doLast {
        val offenders = runtimeIds.get().filterNot { id -> allowedPrefixes.any { id.startsWith(it) } }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                ":engine-conformance must depend on nothing but the SPI, Kotlin, coroutines and JUnit. " +
                    "A driver here would be the suite choosing a fixture. Offending artifacts: " +
                    offenders.distinct(),
            )
        }
    }
}

tasks.named("check") { dependsOn(assertConformanceStaysPortable) }
