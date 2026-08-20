package dev.dbide.core.postgres

/**
 * Removes connection identity from anything on its way to a log or the UI.
 *
 * Driver messages routinely embed the host, port, user, and database — "Connection
 * to db.internal:5432 refused" tells an onlooker where production lives. Nothing
 * leaves the adapter without passing through here.
 */
class Redaction(secrets: Collection<String>) {
    private val secrets: List<String> =
        secrets.filter { it.isNotBlank() && it.length >= 2 }
            .distinct()
            .sortedByDescending { it.length }

    fun scrub(text: String?): String? {
        if (text == null) return null
        var scrubbed: String = text
        for (secret in secrets) {
            scrubbed = scrubbed.replace(secret, REPLACEMENT, ignoreCase = true)
        }
        return URL_PATTERN.replace(scrubbed, REPLACEMENT)
    }

    companion object {
        private const val REPLACEMENT = "[redacted]"
        private val URL_PATTERN = Regex("""jdbc:[a-z0-9]+://\S*""", RegexOption.IGNORE_CASE)

        val NONE = Redaction(emptyList())
    }
}
