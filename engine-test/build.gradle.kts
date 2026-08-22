plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * An engine that exists to prove the seam, and nothing else.
 *
 * Phase 3's acceptance criterion is that adding a `DatabaseEngine` to the classpath
 * makes it appear in the UI with no UI change. A criterion like that cannot be
 * checked by reading the code — the whole failure mode is a list somewhere that the
 * reader forgot about — so it is checked by putting an engine on a test classpath
 * and asserting that the dialog draws it.
 *
 * It depends on `:engine-api` alone. That is deliberate: if proving the seam needed
 * `:core`, the seam would not be where this phase claims it is.
 */
dependencies {
    implementation(project(":engine-api"))
    implementation(libs.kotlinx.coroutines.core)
}
