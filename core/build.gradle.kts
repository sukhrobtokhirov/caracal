plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
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
    environment("DBIDE_INTEGRATION", providers.environmentVariable("DBIDE_INTEGRATION").getOrElse(""))
    // :core must pass with no display server. Running headless makes that fail here
    // rather than on a CI runner that has no screen.
    systemProperty("java.awt.headless", "true")
}
