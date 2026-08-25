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
    // Issue #4 moved two more sharp edges into this module. `QueryResultsKt` is where
    // the SPI's wider value model is narrowed into the grid's, which is a place a
    // value can be lost silently; `CsvStream` is the export loop, whose budgets used
    // to be PostgreSQL's and are now every engine's.
    "dev.caracal.core.result.QueryResultsKt",
    "dev.caracal.core.export.CsvStream",
)
apply(from = rootProject.file("gradle/characterization-coverage.gradle.kts"))
