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
    /**
     * Whether this engine's server says things on the way to a result that did not
     * fail — a `RAISE NOTICE`, a MySQL warning — and this engine surfaces them as
     * [StatementOutcome.Notice].
     *
     * Declared rather than discovered because it is the difference between an engine
     * that swallows its server's output and one whose server has none to swallow.
     * SQLite is the second; a driver that drops PostgreSQL's `DO` block output is the
     * first, and shows a blank grid for a statement whose entire result was a
     * sentence. Without this line the conformance suite cannot tell them apart, and
     * the case it would have to skip is one of the ones worth keeping.
     */
    val surfacesNotices: Boolean,
    /**
     * Whether this engine has a type that carries an exact decimal number.
     *
     * Every engine so far had one and it looked like a property of SQL itself.
     * SQLite is the counterexample that shows it is not: its five storage classes
     * are integer, float, text, blob and null, and a `DECIMAL(30,10)` column is a
     * *declared type*, not a type — a decimal written into one is converted to a
     * float, and the digits past the fifteenth are gone before anything can read
     * them back.
     *
     * Declared rather than discovered, for the reason [surfacesNotices] is: an
     * engine with no exact decimal and an engine that rounds one on the way through
     * produce the same wrong digits, and the conformance suite must fail the second
     * while excusing the first. Excusing it is the whole cost of this line, so it is
     * worth saying what `false` concedes: this engine cannot hold money, and an
     * application storing money in it is choosing text and doing its own arithmetic.
     *
     * `false` does not mean the engine has no numbers. Exact *integers* are asserted
     * of every SQL engine and are not covered here — SQLite has 64-bit integers and
     * keeps every digit of one.
     */
    val exactNumerics: Boolean,
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
