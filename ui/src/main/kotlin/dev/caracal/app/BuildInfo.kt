package dev.caracal.app

import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Properties

/**
 * Which build this is: the three facts a bug report cannot be answered without.
 *
 * They are read from a properties file written by `:app:generateBuildInfo` rather
 * than compiled in, so that reading them costs a classpath lookup and no build-system
 * plumbing reaches into the source. Every field has a fallback, because a build
 * compiled outside a checkout is a legitimate build — it just cannot say which commit
 * it came from, and saying so is more useful than refusing to start.
 */
data class BuildInfo(
    val version: String,
    val commit: String,
    /** The commit's own timestamp, or null where there was no commit to ask. */
    val builtAt: OffsetDateTime?,
) {
    /**
     * One line, for `--version` and for a bug report to be pasted into.
     *
     * ```
     * Caracal 0.1.0 (commit 1a2b3c4, built 2026-08-21T22:46:03+05:00)
     * ```
     */
    fun describe(): String = buildString {
        append("Caracal ")
        append(version)
        append(" (commit ")
        append(commit)
        builtAt?.let {
            append(", built ")
            append(it.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }
        append(")")
    }

    /** The date as a reader of the About panel would write it, or null. */
    fun builtOn(): String? = builtAt
        ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    companion object {
        /** What a build with nothing to read reports. */
        val UNKNOWN = BuildInfo(version = "0.0.0-dev", commit = "unknown", builtAt = null)

        private const val RESOURCE = "/caracal-build.properties"

        /** This build. Read once; the file cannot change while the process runs. */
        val current: BuildInfo by lazy {
            BuildInfo::class.java.getResourceAsStream(RESOURCE)?.use(::read) ?: UNKNOWN
        }

        /**
         * Parses the generated resource, treating every missing or unparseable field
         * as absent rather than as a reason to fail. This runs before the window
         * opens: a malformed date must not be the thing that stops an application
         * from starting.
         */
        fun read(stream: InputStream): BuildInfo {
            val properties = Properties().apply { load(stream) }
            return BuildInfo(
                version = properties.getProperty("version")?.takeIf { it.isNotBlank() }
                    ?: UNKNOWN.version,
                commit = properties.getProperty("commit")?.takeIf { it.isNotBlank() }
                    ?: UNKNOWN.commit,
                builtAt = properties.getProperty("date")?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
            )
        }
    }
}
