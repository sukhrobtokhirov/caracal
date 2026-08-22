package dev.caracal.engine.api

/**
 * What an engine can do, in the form the UI is allowed to ask.
 *
 * The rule this type exists to enforce is one line long: **the UI reads
 * capabilities to decide what to show, and never switches on [EngineId].** A
 * `when (session.engineId)` in the UI is not a shortcut, it is a missing capability
 * or a missing facet, and the fix is to add one here rather than to add an arm
 * there. Nine such switches exist today; each is a Phase 2 target and each should
 * disappear into this record or into a facet lookup.
 */
data class EngineCapabilities(
    val family: EngineFamily,
    val namespaceModel: NamespaceModel,
    val transactions: TransactionSupport,
    val readOnlyEnforcement: ReadOnlyEnforcement,
    val cancellation: CancellationSupport,
    val rowIdentity: RowIdentitySupport,
    val identifierQuote: QuoteStyle,
    val supportsMultipleResultSets: Boolean,
    val supportsExplain: Boolean,
    val supportsSchemaDiff: Boolean,
    /** How long an identifier may be, or 0 for an engine that has no identifiers. */
    val maxIdentifierLength: Int,
    val defaultPort: Int?,
)

/** The broad shape of the data model, which decides which workspace is opened. */
enum class EngineFamily { SQL, KEY_VALUE, DOCUMENT }

/** How many levels of container sit between a connection and a table. */
enum class NamespaceModel { NONE, DATABASE, SCHEMA, DATABASE_AND_SCHEMA }

/**
 * Whether transactions exist and whether the user may drive them.
 *
 * [IMPLICIT] is not a lesser form of [EXPLICIT]: it says every statement runs in a
 * transaction the application opens and closes, which is what happens today, and it
 * is the reason a `SET search_path` reports success and is gone before the next
 * statement runs. The UI needs to know that to explain it.
 */
enum class TransactionSupport { NONE, IMPLICIT, EXPLICIT }

/**
 * How a read-only connection is actually held to it.
 *
 * The distinction is a disclosure, not a detail. A PostgreSQL read-only connection
 * is enforced by the server, where a write hidden inside a function body compiled
 * last year is still a write. A Redis one is enforced by a command allowlist in this
 * process, which holds against a person with the wrong window focused and not
 * against anything else. Presenting the two with identical affordances would be a
 * claim the product cannot back, so the connection list says which is which.
 */
enum class ReadOnlyEnforcement { SESSION_SETTING, CONNECTION_URI, COMMAND_GUARD_ONLY }

/**
 * What happens when the user presses cancel.
 *
 * Declared rather than assumed, because "cancel that actually cancels" is a claim in
 * the README and adding an engine must not quietly make it false. [CLIENT_ABANDON]
 * is the honest name for what a Redis client can do: stop reading and drop the
 * connection, while the server finishes whatever it was told to do.
 */
enum class CancellationSupport {
    /** PostgreSQL: `PGConnection.cancelQuery()` on a side channel. */
    OUT_OF_BAND,

    /** MySQL: `KILL QUERY <id>` from a second connection. */
    SIDE_CONNECTION,

    /** SQLite: `sqlite3_interrupt`, via `Statement.cancel()`. */
    INTERRUPT,

    /** Redis: nothing to cancel with. We stop reading and drop the connection. */
    CLIENT_ABANDON,

    NONE,
}

/** What an editable grid could use to name the row it is about to change. */
enum class RowIdentitySupport { PRIMARY_KEY, PSEUDO_COLUMN, PRIMARY_KEY_OR_PSEUDO, NONE }

/**
 * How an identifier is quoted, applied by the engine and declared here so the UI can
 * render an example. [NONE] is for the engines that have no identifiers to quote.
 */
enum class QuoteStyle { DOUBLE_QUOTE, BACKTICK, BRACKET, NONE }
