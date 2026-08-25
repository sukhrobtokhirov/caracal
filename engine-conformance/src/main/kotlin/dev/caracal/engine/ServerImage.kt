package dev.caracal.engine

/**
 * The container images every integration and conformance suite dials, in one place.
 *
 * Section 10 asks for one version per engine on a pull request and the whole matrix
 * nightly, and a matrix is only expressible if the version is an input rather than a
 * literal repeated in fourteen files. The defaults are the versions a developer gets
 * with no environment set, so nothing about running the suites locally changes.
 *
 * [postgresMajor] and [redisMajor] exist because the version is the one thing a
 * matrix run can assert that a single-version run cannot: an engine that parsed its
 * server's version string against the image it was written on passes every day and
 * fails on the nightly job, which is exactly where that failure is cheap.
 */
object ServerImage {

    val postgres: String = read("CARACAL_POSTGRES_IMAGE", "postgres:16-alpine")

    val redis: String = read("CARACAL_REDIS_IMAGE", "redis:7-alpine")

    val postgresMajor: Int = majorOf(postgres)

    val redisMajor: Int = majorOf(redis)

    private fun read(variable: String, fallback: String): String =
        System.getenv(variable)?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * The major version an image tag promises.
     *
     * `postgres:17`, `redis:7.2-alpine` and `postgres:13-alpine` all have to answer,
     * and an image with no tag or an unparseable one is a mistake worth a sentence
     * rather than a silent zero — the assertion it feeds would otherwise fail with a
     * number nobody could explain.
     *
     * The repository is taken off the front before the tag is looked for, because a
     * registry may carry a port: `mirror.internal:5000/postgres` has a colon and no
     * tag, and reading backwards from the last one turns the port into a major version
     * of 5000. That is the unexplainable number this was written to avoid, produced by
     * the code avoiding it.
     */
    private fun majorOf(image: String): Int {
        val tag = image.substringAfterLast('/').substringAfterLast(':', missingDelimiterValue = "")
        return tag.takeWhile { it.isDigit() }.toIntOrNull()
            ?: error("'$image' does not carry a version tag this suite can assert against.")
    }
}
