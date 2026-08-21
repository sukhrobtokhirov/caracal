package dev.caracal.core.text

/**
 * Removes connection identity from anything on its way to a log or the UI.
 *
 * Driver messages routinely embed the host, port, user, and database — "Connection
 * to db.internal:5432 refused" tells an onlooker where production lives. Nothing
 * leaves an adapter without passing through here.
 *
 * Engine-neutral, and in a neutral package for that reason: PostgreSQL and Redis
 * leak the same four things by the same mechanism, and a Redis error is if anything
 * freer with them — Lettuce puts the address in the message of most connection
 * failures, and a `RAISE`-style server reply can quote whatever it was handed.
 */
class Redaction(
    identity: Collection<String>,
    /**
     * Values redacted whatever their length.
     *
     * [identity] carries a length floor because a one-character user name or
     * database name is a single letter, and redacting every "a" in a driver message
     * destroys the message without hiding anything an onlooker did not already have.
     * A password is the opposite case: it is short only by accident, nothing about
     * it is guessable from context, and exempting it meant the one value that must
     * never be logged was the one value that passed through intact. Nothing forces
     * a password to be two characters, so nothing may assume it.
     */
    passwords: Collection<String> = emptyList(),
) {
    private val secrets: List<String> =
        (identity.filter { it.isNotBlank() && it.length >= 2 } + passwords.filter { it.isNotEmpty() })
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
        /**
         * Anything shaped like a connection string, whichever engine wrote it.
         *
         * `jdbc:postgresql://...` from pgjdbc and `redis://`, `rediss://`, and
         * `redis-socket://` from Lettuce, which builds them for its own messages out
         * of the URI it was given — password included, when one was.
         */
        private val URL_PATTERN =
            Regex("""(?:jdbc:[a-z0-9]+|rediss?|redis-socket|redis-sentinel)://\S*""", RegexOption.IGNORE_CASE)

        val NONE = Redaction(emptyList())
    }
}
