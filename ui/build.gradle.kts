plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

dependencies {
    implementation(project(":engine-api"))
    implementation(project(":engine-sql"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.slf4j.api)

    testImplementation(libs.compose.ui.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(project(":engine-postgres"))
    testRuntimeOnly(project(":engine-redis"))
    testRuntimeOnly(project(":engine-test"))
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

val assertUiHasNoEngineDependencies = tasks.register("assertUiHasNoEngineDependencies") {
    val runtimeIds = configurations.named("runtimeClasspath").flatMap { conf ->
        conf.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.map { it.id.componentIdentifier.displayName }
        }
    }
    inputs.files(configurations.named("runtimeClasspath"))
    doLast {
        val forbidden = listOf(
            "project ':engine-postgres'",
            "project ':engine-redis'",
            "project ':engine-mysql'",
            "project ':engine-sqlite'",
        )
        val offenders = runtimeIds.get().filter { id -> forbidden.any(id::startsWith) }
        if (offenders.isNotEmpty()) {
            throw GradleException(":ui must not depend on engine implementations: ${offenders.distinct()}")
        }
    }
}

tasks.named("check") { dependsOn(assertUiHasNoEngineDependencies) }
