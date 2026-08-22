plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

dependencies {
    // Flow and StateFlow are in the SPI's signatures, so this is the one dependency
    // the module is allowed. Everything else it needs is in the JDK.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * The SPI's whole value is that it is the one thing every module may depend on, and
 * that property is destroyed silently: one convenient import of a driver type, and
 * `:ui` can no longer be kept away from `:engine-postgres` because the interface it
 * talks through already drags it in.
 *
 * So the boundary is a build rule rather than a convention, in the same shape
 * `:core` already uses for Compose. The allowlist is deliberately tiny — Kotlin,
 * coroutines, and the annotations they carry — because anything that has to be
 * argued about is a thing that should not be here.
 */
val allowedPrefixes = listOf(
    "org.jetbrains.kotlin",
    "org.jetbrains.kotlinx:kotlinx-coroutines",
    "org.jetbrains:annotations",
)

val assertSpiHasNoDependencies = tasks.register("assertSpiHasNoDependencies") {
    val runtimeIds = configurations.named("runtimeClasspath").flatMap { conf ->
        conf.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { it.id.componentIdentifier.displayName }
        }
    }
    inputs.files(configurations.named("runtimeClasspath"))
    doLast {
        val offenders = runtimeIds.get().filterNot { id ->
            allowedPrefixes.any { id.startsWith(it) }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                ":engine-api must depend on nothing but Kotlin and coroutines. " +
                    "Offending artifacts: ${offenders.distinct()}",
            )
        }
    }
}

tasks.named("check") { dependsOn(assertSpiHasNoDependencies) }
