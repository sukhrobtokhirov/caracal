package dev.caracal.core.sql

/**
 * Writes a catalog name back as SQL.
 *
 * Everything is quoted, always. An unquoted identifier is folded to lower case by
 * the server, so `Orders` and `orders` are the same name unquoted and different
 * names quoted — and the browser is showing the second kind. Quoting only the
 * names that need it would mean reimplementing PostgreSQL's fold and its reserved
 * word list, and being wrong about either produces SQL that reads correctly and
 * selects from the wrong table.
 *
 * The one escape a quoted identifier has is a doubled `"`, which is legal in a
 * name and therefore has to be handled rather than assumed away.
 */
object Identifiers {

    /** [name] as a quoted identifier. */
    fun quote(name: String): String = buildString(name.length + 2) {
        append('"')
        for (character in name) {
            if (character == '"') append('"')
            append(character)
        }
        append('"')
    }

    /** A schema-qualified name: `"Sales"."Order Items"`. */
    fun qualify(schema: String, name: String): String = "${quote(schema)}.${quote(name)}"
}
