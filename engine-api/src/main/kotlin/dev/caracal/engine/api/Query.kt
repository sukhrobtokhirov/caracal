package dev.caracal.engine.api

import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

/**
 * Running statements: the facet every SQL engine provides and no key-value engine
 * does.
 */
interface QueryFacet {
    /**
     * How this engine's dialect is split into statements.
     *
     * The splitter itself is shared — it is one of the few things §12 lists as
     * safely shared, and the error position mapping that rides on it is the sharpest
     * edge in the product — but what counts as a string, a comment, or a quoted
     * identifier is not. PostgreSQL has `$tag$ ... $tag$` and `E'\n'`; MySQL has
     * backticks and `#` comments and backslash escapes by default. The engine
     * supplies the differences and shares the machinery.
     */
    val splitterConfig: SplitterConfig

    suspend fun execute(request: StatementRequest): StatementExecution
}

/**
 * One statement, on its way to a server.
 *
 * [sourceOffset] is what makes an error land on the right character. The server
 * reports a position within the statement it received; the editor needs one within
 * the whole buffer; the difference is this number plus a code-point aware
 * conversion. A request that does not carry it can still run a statement and can
 * never underline a mistake in it.
 */
data class StatementRequest(
    val sql: String,
    /** 0-based UTF-16 offset of this statement within the editor buffer. */
    val sourceOffset: Int = 0,
    val parameters: List<BoundParameter> = emptyList(),
    val fetchSize: Int = 500,
    val maxRows: Long? = null,
    /** Classified by the engine, gated by core. Never trusted as a security control. */
    val intent: WriteIntent = WriteIntent.UNKNOWN,
)

/** A bound parameter. [typeHint] is the engine's own type name where one is needed. */
data class BoundParameter(val value: CellValue, val typeHint: String? = null)

/**
 * A statement in flight.
 *
 * [outcomes] is cold: collecting it is what runs the statement. That is deliberate,
 * because it makes cancellation mean something — the collector's job is the
 * statement's job, and cancelling one cancels the other through whatever mechanism
 * the engine declared.
 */
interface StatementExecution {
    /** One or more outcomes per submitted statement. */
    val outcomes: Flow<StatementOutcome>

    suspend fun cancel(): CancelResult
}

sealed interface StatementOutcome {
    /**
     * Rows, described and then streamed. The descriptor arrives first because the
     * grid can draw its header before the first row lands.
     */
    data class Rows(val descriptor: ResultDescriptor, val rows: Flow<Row>) : StatementOutcome

    /** [tag] is the server's own command tag where it sends one, and null where it does not. */
    data class UpdateCount(val count: Long, val tag: String? = null) : StatementOutcome

    /** Emitted after [Rows] when something was left behind, and never otherwise. */
    data class Truncated(val reason: TruncationReason) : StatementOutcome

    /**
     * Something the server said on the way to a result that did not fail.
     *
     * `RAISE NOTICE`, a MySQL warning, SQLite `PRAGMA` output. These are the entire
     * output of some statements — a `DO` block whose only job is to report what it
     * found returns no columns and no count — so an application that drops them shows
     * a blank grid and calls it success.
     *
     * §3.2 of the spec models [severity] as an enum. The repository wins: PostgreSQL
     * localizes it through `lc_messages`, so it is a string to show and not a value
     * to branch on. [sqlState] is the field to test against if anything ever needs to.
     */
    data class Notice(
        val text: String,
        val severity: String? = null,
        val sqlState: String? = null,
        val detail: String? = null,
        val hint: String? = null,
    ) : StatementOutcome

    data class Failed(val error: EngineError) : StatementOutcome
}

/** Why a result stopped short of everything the statement would have returned. */
enum class TruncationReason {
    /** More rows matched than the row limit allows. */
    ROW_LIMIT,

    /** The rows were few and large. One `jsonb` document can be a thousand rows of memory. */
    SIZE_LIMIT,
}

/** The shape of a result set, known before its first row. */
data class ResultDescriptor(val columns: List<ColumnDescriptor>, val elapsed: Duration? = null)

/**
 * One column.
 *
 * [typeName] is the engine's own name for the type — `int8`, `timestamptz` — not the
 * JDBC approximation, because it is what the value reader keys on and what a person
 * reads in a header. Names are not unique: `SELECT 1 AS a, 2 AS a` is valid, so a
 * cell is addressed by position and never by name.
 */
data class ColumnDescriptor(
    val name: String,
    val typeName: String,
    val format: ColumnFormat = ColumnFormat.TEXT,
)

data class Row(val cells: List<CellValue>)

/**
 * What actually happened when the user pressed cancel.
 *
 * [ClientAbandoned] must not render like [ServerAcknowledged]. They are different
 * facts: one means the server stopped, the other means we stopped listening and the
 * statement may still be running. Collapsing them is how "cancel that actually
 * cancels" silently becomes false.
 */
sealed interface CancelResult {
    data object ServerAcknowledged : CancelResult

    data object ClientAbandoned : CancelResult

    data class Unsupported(val reason: String) : CancelResult
}

/**
 * The dialect differences a shared statement splitter has to be told about.
 *
 * Small on purpose. Every field here is one a second engine actually needs, and
 * anything that turns out to be genuinely per-engine — error parsing, type mapping,
 * catalog queries — stays in the engine rather than growing another flag.
 */
data class SplitterConfig(
    val statementSeparator: Char = ';',
    /** PostgreSQL's `$tag$ ... $tag$`, which nests and contains anything. */
    val dollarQuotedStrings: Boolean = false,
    /** PostgreSQL's `E'...'`, where a backslash escapes. */
    val escapedStringLiterals: Boolean = false,
    /** MySQL treats a backslash as an escape inside an ordinary literal; standard SQL does not. */
    val backslashEscapesStrings: Boolean = false,
    val lineCommentPrefixes: List<String> = listOf("--"),
    val identifierQuote: QuoteStyle = QuoteStyle.DOUBLE_QUOTE,
)
