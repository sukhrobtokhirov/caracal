plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `java-library`
}

dependencies {
    api(project(":engine-api"))
    implementation(project(":engine-sql"))
    // For `Redaction` alone. SQLite has no password, but it has an address — the file
    // path — and `DbError`'s contract forbids one in a message just as firmly.
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.hikaricp)
    implementation(libs.slf4j.api)

    testImplementation(project(":engine-conformance"))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

extra["characterizedClasses"] = listOf(
    // The two files where a value can be lost or an address can escape, and neither
    // is reachable from the conformance suite alone: the suite asserts the handful of
    // shapes every engine has, and SQLite's dynamic typing has more of them than that.
    "dev.caracal.engine.sqlite.SqliteValues",
    "dev.caracal.engine.sqlite.SqliteErrors",
)
apply(from = rootProject.file("gradle/characterization-coverage.gradle.kts"))
