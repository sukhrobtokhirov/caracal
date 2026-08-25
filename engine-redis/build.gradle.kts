plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `java-library`
}

dependencies {
    api(project(":engine-api"))
    implementation(project(":engine-sql"))
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.lettuce)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":core")))
    testImplementation(project(":engine-conformance"))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    val integration = providers.environmentVariable("CARACAL_INTEGRATION").getOrElse("")
    environment("CARACAL_INTEGRATION", integration)
    inputs.property("caracalIntegration", integration)
    systemProperty("java.awt.headless", "true")
    val image = providers.environmentVariable("CARACAL_REDIS_IMAGE").getOrElse("")
    environment("CARACAL_REDIS_IMAGE", image)
    inputs.property("CARACAL_REDIS_IMAGE", image)
    if (integration == "1") systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
}

extra["characterizedClasses"] = listOf("dev.caracal.core.redis.RedisCommandGuard")
apply(from = rootProject.file("gradle/characterization-coverage.gradle.kts"))
