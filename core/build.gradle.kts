plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    // `api` needs it, and `api` is the right configuration for :engine-api: the SPI
    // is in the signatures :core hands out, not an implementation detail behind them.
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":engine-api"))
    api(project(":engine-sql"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.sqlite.jdbc)
    implementation(libs.bouncycastle)

    testImplementation(project(":engine-postgres"))
    testImplementation(project(":engine-redis"))
    testImplementation(project(":engine-sqlite"))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.core)
    // The tests every engine runs. Section 10, as a dependency rather than a file.
    testImplementation(project(":engine-conformance"))
    // Runs the conformance suite against a deliberately broken engine from inside a
    // test, and reads back which cases failed. See BrokenEngineIsCaughtTest.
    testImplementation(libs.junit.platform.testkit)
    // An engine written by somebody else, as far as the tests are concerned.
    // See engine-test/build.gradle.kts for what it is proving.
    testRuntimeOnly(project(":engine-test"))
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
    // Resolving a classpath that contains a project dependency means building that
    // project's jar first, and Gradle will not let a task read the answer before the
    // producing task has run. Declaring the configurations as inputs is what carries
    // that ordering across; without it the check fails the moment :core gains its
    // first project dependency, which is exactly what :engine-api is.
    inputs.files(configurations.named("runtimeClasspath"))
    inputs.files(configurations.named("testRuntimeClasspath"))
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
    // The image the container suites dial, so a matrix run reaches the test JVM and
    // re-runs the tests rather than finding them up to date.
    listOf("CARACAL_POSTGRES_IMAGE", "CARACAL_REDIS_IMAGE").forEach { variable ->
        val image = providers.environmentVariable(variable).getOrElse("")
        environment(variable, image)
        inputs.property(variable, image)
    }
    // The conformance suite's redaction case greps every log line for the fixture
    // password, and a line that is never emitted cannot be caught leaking. Debug is
    // where a driver writes the connection URL, so the integration runs turn it on;
    // the output goes to the test report rather than the console, which is where
    // somebody would go looking for it anyway.
    if (integration == "1") {
        systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
    }
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
extra["characterizedClasses"] = listOf("dev.caracal.core.policy.DataSafetyPolicy")
apply(from = rootProject.file("gradle/characterization-coverage.gradle.kts"))
