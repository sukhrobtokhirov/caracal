plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kover) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // A @Test method that returns a value is not a test method, and JUnit's
            // default is to quietly not discover it. Three M1 acceptance tests were
            // dark that way — including the one asserting that locking closes live
            // clients — because their last expression happened to return something.
            // Promoting discovery issues to failures turns that silence into a build
            // error.
            systemProperty("junit.platform.discovery.issue.severity.critical", "INFO")
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
