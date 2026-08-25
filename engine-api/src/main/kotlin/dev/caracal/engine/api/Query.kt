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
    /**
     * The most rows to read, or null for whatever bound the session already carries.
     *
     * A ceiling and not a promise of that many: a result shorter than this simply
     * ends. An engine that cuts a result here says so with
     * [StatementOutcome.Truncated], because a result that stopped at a limit and one
     * that ran out of rows are the same list and completely different facts.
     */
    val maxRows: Long? = null,
    /**
     * How much of an oversized value the caller wants.
     *
     * §3.2 of the spec does not have this field and the repository needs it, because
     * the two callers of a statement disagree about a value that is larger than a
     * screen. A grid clips one, because a person is looking at a single cell and the
     * rest of the row has to fit beside it. A file does not, because a CSV whose cells
     * are silently prefixes of the real ones is worse than no CSV — it looks complete,
     * and nothing reading it can tell that it is not.
     *
     * Without the field the export had to reach past the SPI to say so, which is what
     * `LegacySqlAdapter` was, and it is the caller's decision rather than the engine's:
     * the same engine serves both.
     */
    val values: ValueDetail = ValueDetail.PREVIEW,
    /**
     * How long the server is given, or null for the session's own limit.
     *
     * Also not in §3.2, and needed for the same reason [values] is. A session's
     * statement timeout is chosen for a person waiting at a keyboard; an export is a
     * file being written for minutes on purpose, and running it under the interactive
     * limit means every large export fails at thirty seconds. The session's limit
     * stays the default, so a caller that has no opinion is not asked for one.
     */
    val timeout: Duration? = null,
    /** Classified by the engine, gated by core. Never trusted as a security control. */
    val intent: WriteIntent = WriteIntent.UNKNOWN,
)

/**
 * How much of a value that is too large to show whole the caller wants.
 *
 * The difference is what the value is *for*, which is why it is asked of the caller
 * and not declared by the engine. [PREVIEW] is a cell in a grid: bounded, marked as
 * bounded, and accompanied by the true size so the reader knows what is missing.
 * [FULL] is a cell in a file, where there is no reader to mark anything for and a
 * prefix would be indistinguishable from the whole value.
 *
 * [FULL] lifts the *per-value* bound only. The row bound is [StatementRequest.maxRows]
 * and it still applies, because an unbounded row count is not a large value, it is an
 * unbounded amount of work.
 */
enum class ValueDetail {
    /** Bounded per value, and every bound is declared on the value that was cut. */
    PREVIEW,

    /** Whole values. For an export, and for nothing that is retained. */
    FULL,
}

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
     *
     * **[rows] is live only for as long as this outcome is being delivered.** An
     * engine that streams holds a cursor, a transaction and a pooled connection open
     * to produce it, and the only place it can safely let go of all three is after
     * the collector has finished with them — so the collector must consume [rows]
     * inside the block that received this outcome, and never keep the flow to collect
     * later. Collecting it late finds a closed result set; not collecting it at all
     * is allowed and means the rest of the result is abandoned, which is what closing
     * a tab mid-query does.
     *
     * That is a real constraint and it is the price of not materializing. The
     * alternative — hand back a list — is an export of a few hundred megabytes
     * assembled in heap on the way to disk, which is the failure this shape exists to
     * make impossible.
     */
    data class Rows(val descriptor: ResultDescriptor, val rows: Flow<Row>) : StatementOutcome

    /** [tag] is the server's own command tag where it sends one, and null where it does not. */
    data class UpdateCount(val count: Long, val tag: String? = null) : StatementOutcome

    /**
     * Emitted after [Rows] when something was left behind, and never otherwise.
     *
     * After, because nothing knows a result was cut until the reading stops. A
     * collector that abandoned [Rows.rows] part way through never learns whether the
     * rest would have fitted, and is not told: it stopped reading of its own accord,
     * and reporting a limit it did not reach would be inventing one.
     */
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
