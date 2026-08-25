plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `java-library`
}

dependencies {
    api(project(":engine-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

extra["characterizedClasses"] = listOf(
    "dev.caracal.core.sql.Statement",
    "dev.caracal.core.sql.StatementSplitter",
    "dev.caracal.core.sql.StatementClassifier",
    "dev.caracal.core.export.CsvWriter",
)
apply(from = rootProject.file("gradle/characterization-coverage.gradle.kts"))
