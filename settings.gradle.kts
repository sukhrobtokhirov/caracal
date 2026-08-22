rootProject.name = "caracal"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform resolves parts of androidx from Google's repository.
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

include(":engine-api", ":core", ":app")

// Not shipped. It is on the test runtime classpath of :core and :app, where it
// stands in for an engine written by somebody else — see engine-test/build.gradle.kts.
include(":engine-test")
