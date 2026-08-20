# M2 — PostgreSQL Read Path

## Outcome

Deliver the first genuinely useful database workflow: open a saved PostgreSQL connection, browse its schemas and objects, write SQL with syntax highlighting, execute or cancel a statement, inspect correctly encoded results in a virtualized read-only grid, and export tabular results to CSV.

This milestone establishes the application's core trust contract. Values shown in the grid must be faithful to PostgreSQL, large results must remain bounded, and users must be able to stop expensive work.

## User-visible workflow

1. Select and open a PostgreSQL connection.
2. Expand schemas, tables/views, and columns in a lazy schema tree.
3. Write SQL in a CodeMirror editor.
4. Select text or place the cursor in a statement and run it.
5. See running state and cancel if needed.
6. Inspect column names, PostgreSQL types, exact values, NULLs, row count, duration, truncation state, notices, or a positioned error.
7. Export a successful tabular result as CSV.

## Scope

### Included

- Lazy schema/object/column browser based on `pg_catalog`
- CodeMirror 6 SQL editor and basic PostgreSQL highlighting
- PostgreSQL-aware statement splitter
- Execute and cancel lifecycle with unique query IDs
- Bounded query results and explicit truncation
- Type-safe server-to-frontend result encoding
- Virtualized, read-only result grid
- CSV export for successful tabular queries
- Production/write confirmations and server-side read-only enforcement
- Query-history recording at execution time; the history UI comes in M4

### Not included

- Schema-aware autocomplete
- Editable result cells or row mutations from the grid
- Server-side cursors or long-lived pagination transactions
- Visual query plans
- Schema diff, migrations, or object editing
- Multiple simultaneous result grids for one script
- Full stored-procedure debugger

## API contract

Implement the PostgreSQL endpoints from the source plan:

```text
GET  /api/pg/:id/schemas
GET  /api/pg/:id/objects?schema=&kind=table|view|function
GET  /api/pg/:id/columns?schema=&table=
POST /api/pg/:id/query
POST /api/pg/:id/cancel
GET  /api/pg/:id/export?queryId=
```

All routes must verify that the connection exists, is PostgreSQL, is unlocked, and is open. A closed connection returns a stable `connection_not_open` error rather than opening implicitly.

Suggested query request:

```json
{
  "sql": "select id, total from invoices order by id",
  "limit": 1000,
  "confirmation": null
}
```

Suggested successful response:

```json
{
  "queryId": "0195...",
  "columns": [
    {"name": "id", "typeOid": 20, "typeName": "int8", "format": "integer"},
    {"name": "total", "typeOid": 1700, "typeName": "numeric", "format": "decimal"}
  ],
  "rows": [["9007199254740993", "1234567890.00000001"]],
  "rowCount": 1,
  "commandTag": "SELECT 1",
  "durationMs": 12,
  "truncated": false,
  "notices": []
}
```

The `limit` is a requested value capped by the server's configured maximum. Never let the frontend raise the hard safety limit.

## Work packages

### 2.1 Define query execution state

Assign a cryptographically unpredictable query ID before work begins. Maintain an in-memory execution registry containing:

- query ID and connection ID;
- start time and current state (`running`, `completed`, `error`, `cancelled`);
- the execution `context.CancelFunc` while running;
- safe result/export metadata after completion;
- a short expiry time for completed entries.

Register before acquiring a pool connection so cancellation also covers pool waits. Remove the cancel function as soon as execution ends. Periodically evict completed records so query text and result metadata do not grow without bound.

The cancel route must be idempotent:

- running query: invoke cancellation and return `202`;
- already finished/cancelled: return the current terminal state;
- unknown/expired query ID: return `404`.

Scope the lookup to both route connection ID and query ID so an identifier from another connection cannot be cancelled or exported accidentally.

### 2.2 Implement the PostgreSQL statement splitter

Do not split on semicolons. Build a small deterministic lexer that recognizes enough PostgreSQL syntax to identify statement boundaries without trying to parse SQL semantics.

The lexer must track:

- ordinary single-quoted strings and doubled quote escapes;
- escape strings such as `E'line\\n'`, including backslash escapes;
- double-quoted identifiers and doubled double quotes;
- `--` line comments through newline or end of input;
- nested `/* ... */` block comments;
- dollar-quoted strings with empty tags (`$$...$$`) and named tags (`$body$...$body$`);
- semicolons only when none of the above states is active.

Return statement text plus byte offsets into the original script. Offsets let the editor identify the statement under the cursor and translate PostgreSQL error positions back to the document.

Rules:

- Ignore empty/comment-only segments.
- Preserve statement text exactly; do not normalize or rewrite SQL.
- Accept a final statement without a semicolon.
- Return a structured lexical error for an unterminated quote/comment/dollar block.
- Treat Unicode as UTF-8 and be explicit about whether offsets are bytes or editor character positions. Add a conversion helper instead of mixing the two.

The backend splitter is authoritative. A small frontend helper may choose the candidate range for responsive editor UX, but the server must validate that the submitted text is one allowed execution unit.

Minimum test corpus:

- two ordinary statements;
- semicolon inside each quote/comment type;
- nested block comments;
- PL/pgSQL function body in `$$`;
- tagged dollar quote containing another dollar-like token;
- escaped quote in `E''`;
- quote/comment at end of file;
- empty statements and comment-only input;
- CRLF and non-ASCII text before an error offset;
- every unterminated state.

Fuzz the splitter with the invariant that it never panics, hangs, or returns overlapping/out-of-range spans.

### 2.3 Define editor execution semantics

Use one predictable rule:

- If text is selected, execute exactly the selected text after backend validation.
- Otherwise execute the statement containing the primary cursor.
- If the cursor is between statements, choose the next non-empty statement; if none exists, choose the previous one.
- The normal Run action executes one statement. A separate **Run script** action can execute multiple statements sequentially only if it is included without jeopardizing the milestone; it is not required for exit.

Before sending, show the statement range visually. Disable duplicate Run actions for the same editor while it is active, but do not freeze schema browsing or other tabs.

CodeMirror configuration should include PostgreSQL SQL highlighting, line numbers, bracket matching, search, selection, undo/redo, and accessible keyboard focus. Autocomplete remains intentionally lexical/basic in v0.1.

> **Deviation, 2026-08-21 — the editor is `BasicTextField`, not RSyntaxTextArea.**
> Plan [§5.8](../../db-ide-mvp-plan.md#58-the-sql-editor) recommends RSyntaxTextArea
> inside a `SwingPanel` and names `BasicTextField` as the permitted fallback. The
> fallback was taken, for two reasons the prototype made concrete: a `SwingPanel`
> renders in its own layer, so Compose cannot draw over it — the write confirmation
> §2.4 needs would clip against the editor's rectangle — and it is invisible to
> `ComposeUiTest`, which would leave every behaviour this section specifies verifiable
> only by hand.
>
> What that costs: highlighting re-runs over the document on every keystroke, so it is
> capped at `SqlHighlighting.DEFAULT_LIMIT` characters and the rest of a very large
> script renders as plain text — still editable, still executable. Search and code
> folding are not there; M4 owns the editor's keyboard work. Everything else in the
> list above is: PostgreSQL highlighting, line numbers that survive wrapping, bracket
> matching, selection, undo/redo, and focus.
>
> **Run script is not implemented.** This section makes it optional, and every
> statement runs in its own read-only transaction, so a sequential run would offer
> none of the atomicity that would justify its failure modes in v0.1.

### 2.4 Enforce data-safety policy

The result grid being read-only does not automatically make SQL safe. Classify the statement conservatively for UI warnings, then enforce database permissions and transaction mode on the server.

- On a connection marked `read_only`, run statements in a read-only transaction or reject anything not accepted by the chosen read-only execution policy.
- On writable development/staging connections, require confirmation for statements classified as writes or DDL.
- On `prod`, require a stronger typed confirmation for writes/DDL and keep the production banner visible in the confirmation.
- Never rely only on the first SQL keyword for actual security; common table expressions and functions can hide writes.
- Document that the database user's grants remain the ultimate protection. Recommend read-only database roles for production browsing.

For MVP, a conservative classifier can power warnings for `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `TRUNCATE`, `CREATE`, `ALTER`, `DROP`, `GRANT`, `REVOKE`, `COPY`, `CALL`, `DO`, `VACUUM`, and similar commands. When classification is uncertain on a read-only connection, prefer denial or PostgreSQL `READ ONLY` transaction enforcement.

Set a configurable statement timeout with a safe default. The timeout is independent from explicit user cancellation and should produce a distinct message.

> **Decision, 2026-08-21 — the `Read only` flag became load-bearing.**
> Until now `PostgresDataSources` opened *every* pool read-only, whatever the saved
> connection said, and the checkbox was a badge with nothing behind it. That is a
> stronger guarantee than this section describes, and it is also one that makes the
> section unbuildable: "require confirmation on writable development and staging
> connections" has no meaning if no connection is writable, and the dialog would
> have been code that could never run.
>
> So the flag now decides how the pool is built. What that buys, and what it costs:
>
> - **Read-only connections** open every connection in a PostgreSQL `READ ONLY`
>   transaction, exactly as before, and the editor additionally refuses a classified
>   write before it is sent — a better sentence than SQLSTATE 25006 arriving half a
>   second after a `DELETE` went to production. The pool remains the guarantee; the
>   pre-flight refusal is only the explanation.
> - **Writable connections** commit a successful statement instead of rolling it
>   back. This is the part with a cost, and it is stated in `PostgresAdapter.endTransaction`:
>   a committed `SET` persists on that pooled connection, where a rolled-back one did
>   not. That is why `StatementClassifier` keeps session statements in a case of
>   their own.
> - **New connections default to read only**, and migration 2 sets `read_only = 1`
>   on every connection saved before this release. Those rows recorded a checkbox
>   that did nothing, so they are not evidence that anyone wanted writes; leaving
>   them at 0 would have silently turned every existing connection writable.
>
> Plan §9's risk table says "MVP is read-only for a reason. Keep it that way until
> the foundations are solid." The foundations named there are what this section
> builds: enforcement in the pool rather than the UI, a classifier that errs toward
> warning, a typed acknowledgement on production, and the database's own grants as
> the boundary underneath all of it. The default is still read only. What changed is
> that turning it off now does something, and does it behind a dialog.
>
> **The statement timeout** defaults to 30 seconds (`PostgresAdapter.DEFAULT_STATEMENT_TIMEOUT`)
> and is configured on `ConnectionRegistry`, which passes it to every session it
> opens. One caveat is worth recording because it is a real trap: JDBC's
> `setQueryTimeout` takes whole seconds and reads zero as *no limit*, so a sub-second
> timeout truncates to no timeout at all. It is rounded up instead — one second is
> the tightest limit this API can express.

### 2.5 Execute with a bounded result

Start with the simple strategy from the source plan:

1. Acquire a pool connection with the query context.
2. Execute the single statement.
3. If rows are returned, read field descriptions first.
4. Iterate at most `hardLimit + 1` rows.
5. Keep only `hardLimit` rows; the extra row establishes `truncated: true`.
6. Close rows immediately and release the connection.
7. Record duration, row count, status, and a sanitized error in query history.

Use a default and hard cap of 1,000 rows for v0.1. A **Fetch more** control may re-run with a higher bounded cap only if the server still enforces an absolute maximum; real cursor pagination is deferred.

Limit response bytes as well as row count. A few enormous text/JSON/`bytea` values can exhaust memory even with 1,000 rows. Apply a per-cell preview limit and total-response limit, return truncation metadata, and never generate invalid UTF-8.

Command-only results should return an empty `columns`/`rows` pair plus `commandTag` and affected row count when available.

### 2.6 Preserve PostgreSQL type fidelity

Build an explicit server-side encoder driven by field OID and PostgreSQL text representations. Do not rely on Go's generic JSON encoding for arbitrary driver values.

Wire-value rules:

- `NULL` becomes JSON `null`.
- Boolean values may be JSON booleans.
- All other values are strings in PostgreSQL-compatible text form.
- `int8`/`bigint` and `numeric` are always strings to avoid JavaScript precision loss.
- `bytea` uses a bounded hexadecimal preview with metadata indicating truncation.
- `timestamptz` is formatted consistently with an explicit offset. Pick UTC or local display once; UTC is the least ambiguous storage representation, while the UI may offer a labeled local rendering later.
- `date`, `time`, and `timestamp` retain enough precision and never pass through JavaScript `Date` implicitly.
- Arrays, JSON/JSONB, UUID, inet/cidr, ranges, intervals, enums, domains, and composite/unknown types use explicit text encoders or a safe string fallback.

Column metadata should include at least name, type OID, database type name, and a display-format hint. Duplicate column names are valid; address cells by column position, not name.

Test exact values around JavaScript's safe-integer boundary, high-precision numeric scales, infinities/NaN where PostgreSQL allows them, empty string versus NULL, zero-length `bytea`, time zones, multidimensional arrays, JSON scalar/object/array values, and unknown extension types.

### 2.7 Build the virtualized result grid

Use TanStack Table for column behavior and TanStack Virtual for row rendering. Virtualization is required even with a 1,000-row default because wide and tall cells can still create expensive DOM trees.

Grid behavior:

- Sticky headers with column name and PostgreSQL type.
- Horizontal scrolling for wide results.
- Row numbers separate from returned columns.
- Plain text cells by default; never inject database values as HTML.
- Visually distinct dimmed italic `NULL` and a distinguishable empty string.
- Right alignment for numeric display hints; consistent boolean rendering.
- Bounded single-line preview with an accessible expanded-value panel.
- JSON pretty view when the type is JSON/JSONB, while preserving the original value for copying/export.
- Copy cell, copy row, and copy selected rows with predictable tab/newline escaping if time permits.
- Clear status bar for duration, returned rows, affected rows, and truncation.

Column resizing and basic sorting may be local-only. Do not imply that sorting the first 1,000 rows sorts the database result; label client-only operations or defer them.

> **Deviation, 2026-08-21 — the grid is hand-built, and sorting is deferred.**
> TanStack Table and TanStack Virtual are React libraries and left with the browser;
> plan [§5.9](../../db-ide-mvp-plan.md#59-the-result-grid) already budgets the grid as
> this project's own code. It virtualizes both axes — rows through `LazyColumn`,
> columns through `columnWindow` — because a result can be wide as well as tall.
>
> Client-side sorting is not implemented rather than implemented and labelled: the
> honest label would say it sorts the first thousand rows and not the query, which is
> a control that has to be explained every time it is used. `ORDER BY` is one line
> away in the editor above it.

### 2.8 Implement schema browsing

Use `pg_catalog`, not `information_schema` alone, so object identity and PostgreSQL-specific metadata remain available.

`GET /schemas` returns schema name, owner when permitted, and whether it is a system schema. Show user schemas by default with a toggle for `pg_catalog`, `information_schema`, and temporary/toast schemas.

`GET /objects` accepts an exact schema and validated kind. Return stable identifiers, name, kind, and small useful metadata such as table/view and estimated row count when inexpensive. Never interpolate schema/kind directly into SQL; pass names as parameters and map kind through a fixed allowlist.

`GET /columns` returns ordinal, name, formatted type, nullable flag, default expression, and key indicators when cheaply available. Use schema plus table identity carefully so quoted/mixed-case names work.

Frontend tree behavior:

- Load only schemas initially.
- Fetch objects when a schema is expanded.
- Fetch columns when an object is expanded.
- Cache within the open connection but provide Refresh at connection/schema/object level.
- Preserve expanded state during unrelated query execution.
- Show permission errors on the affected node rather than replacing the whole tree.
- Insert fully quoted identifiers into the editor on an explicit action or double-click.

Functions may initially show name and signature only. Object definition viewers are not required.

### 2.9 Surface PostgreSQL errors accurately

Map `pgconn.PgError` fields into a safe response:

- severity;
- SQLSTATE code;
- primary message;
- detail and hint when safe;
- position/internal position;
- schema/table/column/constraint identifiers when present.

Do not expose server file, line, routine, connection string, or backend stack details by default.

PostgreSQL error `Position` is a 1-based character position in the submitted statement. Convert it to the corresponding editor document range using the statement's starting offset and UTF-8-aware mapping. Highlight the position and scroll it into view. If mapping is unavailable, show the message without guessing.

Cancellation should display **Cancelled** rather than a generic red failure. Timeouts should say that the configured limit was reached.

> **Note, 2026-08-21 — where each piece ended up.**
> `PostgresErrors` copies the server's report onto `DbError.QueryFailed`: severity,
> SQLSTATE, message, detail, hint, position, internal position, and the object it
> names (`ErrorSubject`). `file`, `line`, `routine`, and the `WHERE` context stack
> are dropped, the last one because it quotes function bodies the user may never
> have been shown. Everything that survives goes through `Redaction` first.
>
> `Failure` carries the whole `QueryFailed` rather than flattening it to a sentence
> — otherwise the position the editor needs and the hint that is frequently the
> entire answer are thrown away between `:core` and the banner.
>
> Position mapping happens in `EditorViewModel.send`, while the statement that was
> sent is still in hand, through `Statement.documentIndex` — which converts from
> PostgreSQL's 1-based *character* count to Kotlin's UTF-16 offsets. `internalPosition`
> is reported and never used to point at anything: it counts into a query the server
> generated, so there is nothing on screen for it to land on, which is §2.9's
> "show the message without guessing".

### 2.10 Export CSV safely

The export route uses a completed, unexpired query ID. Store only the minimum metadata needed for export and expire it quickly.

For v0.1, re-run only a single row-producing statement inside a PostgreSQL read-only transaction and stream its complete result with explicit row, byte, and duration limits. This avoids retaining an unbounded result in process memory and prevents export from repeating a write. Tell the user that export re-runs the query, so rapidly changing data may differ from the preview.

CSV requirements:

- UTF-8 output with a header row.
- RFC 4180-style quoting through Go's `encoding/csv`.
- Empty database strings remain empty quoted/unquoted CSV fields as encoded by the library.
- Choose and document a NULL representation; an empty field is ambiguous, so default to `NULL` or expose a future option.
- Preserve exact textual values from the type encoder.
- Set `Content-Type` and a sanitized `Content-Disposition` filename.
- Stop database work immediately if the client disconnects.
- Do not buffer the entire file before writing.

If the original statement cannot be guaranteed read-only, disable export and explain why.

### 2.11 Record query history

Insert one `query_history` row after every attempted execution, including error and cancellation:

- connection ID;
- exact submitted statement;
- duration;
- returned/affected row count where meaningful;
- `ok`, `error`, or `cancelled`;
- sanitized primary error text;
- execution timestamp.

Do not store parameter values separately or log the statement. History is sensitive local data; its viewing/deletion UI is part of M4. Consider a configurable retention cap, but do not add complex policy UI in this milestone.

## Testing

### Unit tests

- Exhaustive splitter corpus plus fuzzing
- Statement-selection offset mapping
- Read-only/write classification and confirmation policy
- OID/type encoders and preview truncation
- PostgreSQL error mapping and Unicode position conversion
- CSV quoting, NULL representation, filename sanitization, and cancellation
- Execution-registry state transitions, expiry, and cross-connection isolation
- Schema query input validation and quoted identifiers

### Integration tests

Run against supported PostgreSQL versions in CI where practical. Create fixtures covering:

- mixed-case schema/table/column names;
- tables, views, materialized views if exposed, and functions;
- each important scalar and structured type;
- long-running query cancellation using `pg_sleep`;
- statement timeout;
- permission-limited user;
- server-enforced read-only transaction;
- result truncation and CSV streaming;
- backend disconnect during execution.

### Frontend tests

- schema-tree lazy loading, refresh, and node-local errors;
- selection/current-statement execution behavior;
- running/cancelled/success/error states;
- NULL versus empty string and precision-safe values;
- virtualized rendering and expanded-value panel;
- production/write confirmation and read-only denial;
- CSV availability only for eligible completed queries.

### Manual acceptance scenario

1. Open a real PostgreSQL connection with several schemas.
2. Expand a table and insert a correctly quoted name into the editor.
3. Run a query returning `bigint`, high-precision `numeric`, NULL, empty string, `bytea`, `timestamptz`, array, and JSONB values.
4. Confirm values are exact and visually unambiguous.
5. Run a result larger than 1,000 rows and confirm it is bounded and labeled truncated.
6. Start `SELECT pg_sleep(30)` and cancel it; confirm the editor remains usable.
7. Trigger a syntax error after non-ASCII text and confirm the correct location is highlighted.
8. Export an eligible query, inspect quoting and NULL values, and cancel a large export.
9. Attempt a write on a read-only connection and confirm the server prevents it.
10. Attempt a write on a production-tagged writable connection and confirm typed acknowledgement is required.

## Completion checklist

- [ ] Schemas, objects, and columns load lazily from `pg_catalog`.
- [ ] CodeMirror supports highlighted SQL and deterministic statement execution.
- [ ] The statement splitter handles all PostgreSQL quote/comment forms and is fuzz-tested.
- [ ] Every execution has a query ID and working cancellation.
- [ ] Results are bounded by rows and bytes.
- [ ] Type encoding preserves precision and distinguishes NULL from empty strings.
- [ ] The result grid is virtualized and never interprets values as HTML.
- [x] Read-only and production safety policies are enforced server-side.
- [ ] Eligible results stream to valid CSV with bounded resource use.
- [ ] Every execution records history without leaking query text into normal logs.

## Exit criterion

Use the application instead of `psql` for a real work query, including schema discovery, execution, inspection, cancellation, and export, without losing type fidelity or reaching for another tool.
