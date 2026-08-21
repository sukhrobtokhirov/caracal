package dev.dbide.app

import dev.dbide.core.connections.ConnectionView

/**
 * What the connection switcher shows for what has been typed into it.
 *
 * §4.5 permits fuzzy or substring matching "over the small local list", and small is
 * the operative word: this is a list of the servers one person has saved, so a
 * substring test is not a compromise — it is the whole of what is needed, and it has
 * the property fuzzy matching does not, which is that every row on screen visibly
 * contains what was typed.
 *
 * **Only the name is matched.** The host and the database are tempting and wrong: a
 * connection surfacing under a word that appears nowhere on its row is a row the user
 * reads as a bug, and the fix — printing the host on every row to explain the match —
 * costs the switcher the thing it is for, which is being scannable at a glance.
 */
object ConnectionSearch {

    /**
     * [connections] narrowed to [query], with the likeliest answer first.
     *
     * A name that *starts* with what was typed goes above one that merely contains it,
     * because three letters typed into a switcher are the beginning of a name far more
     * often than they are the middle of one. Beyond that the caller's order is kept —
     * the sort is stable — so the switcher lists servers in the same order as the
     * sidebar behind it rather than inventing a second one for the user to learn.
     */
    fun match(connections: List<ConnectionView>, query: String): List<ConnectionView> {
        val needle = query.trim()
        if (needle.isEmpty()) return connections
        return connections
            .filter { it.config.name.contains(needle, ignoreCase = true) }
            .sortedBy { if (it.config.name.startsWith(needle, ignoreCase = true)) 0 else 1 }
    }
}
