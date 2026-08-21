# M4 — Product Polish

## Outcome

Turn the PostgreSQL and Redis feature paths into a coherent daily-use application. Add query history, keyboard-first navigation, durable-in-session tabs, consistent error presentation, production/read-only context, theme support, and deliberate loading/empty/offline states.

This milestone should remove friction, not add major database capabilities. If a polish task needs a new query engine, storage model, or collaboration system, move it to v0.2.

## User-visible workflow

- Reopen or rerun a recent query from searchable history.
- Keep several SQL tasks open as tabs and switch without losing editor state.
- Run SQL with `Cmd/Ctrl+Enter` and switch connections with `Cmd/Ctrl+K`.
- See exactly which connection/environment a tab will use before executing.
- Understand errors where they occur, including PostgreSQL error highlighting.
- Use the application comfortably in light, dark, or system theme.
- Encounter helpful first-run and no-data states rather than blank panes.

## Scope

### Included

- Query-history API and panel
- Query-tab lifecycle and unsaved-change protection
- Keyboard shortcuts and shortcut reference
- Connection switcher/command palette
- Consistent error, loading, empty, expired, and disconnected states
- PostgreSQL error-position highlighting completion
- Light/dark/system theme
- Accessibility and focused usability pass
- Performance and normal-use quality checks

### Not included

- Cloud-synced or cross-device tabs/history
- General-purpose command palette/plugin system
- Custom theme editor
- AI query generation or explanation
- Saved snippets/folders beyond reopening history
- Collaborative sessions
- New database engines or write-grid behavior

## Work packages

### 4.1 Expose paged query history

The `query_history` table already exists and M2 records executions. Add a small authenticated API:

```text
GET    /api/history?connectionId=&status=&before=&limit=
DELETE /api/history?connectionId=
```

`GET` requirements:

- Default to a bounded recent page, such as 50 entries, with a hard maximum.
- Use keyset pagination based on `(executed_at, id)` rather than offset pagination.
- Allow an optional exact connection ID and status filter.
- Return the connection's current safe display information. Because the source schema cascades history deletion with a connection, no orphaned history rows are expected.
- Return statement, duration, row count, status, safe error, and execution time.
- Never read all history into memory.

Suggested response:

```json
{
  "items": [
    {
      "id": 812,
      "connectionId": "0195...",
      "connectionName": "Local PG",
      "statement": "select now()",
      "durationMs": 8,
      "rowCount": 1,
      "status": "ok",
      "error": null,
      "executedAt": "2026-08-20T09:14:00Z"
    }
  ],
  "nextBefore": "opaque-cursor"
}
```

Use an opaque cursor so the frontend does not construct SQLite predicates. `DELETE` clears either all history or one connection after an explicit confirmation and Origin check. It does not delete saved connections.

History contains sensitive SQL. Keep it local, exclude it from normal logs, and make clearing discoverable. Do not send history to analytics or crash services.

### 4.2 Build the history panel

The history panel should support:

- newest-first grouped display by day;
- connection name/color/environment;
- success/error/cancelled state;
- duration and row count;
- a one- or two-line SQL preview rendered as plain text;
- expand to view the complete statement and safe error;
- copy statement;
- open in a new SQL tab;
- replace the active editor only after unsaved-change confirmation;
- load older entries using the API cursor;
- filter by active connection and status;
- clear with explicit scope confirmation.

Rerunning history is never automatic. Opening an entry copies text into an editor; the normal production/read-only confirmations still apply when the user presses Run. Deleting a connection also deletes its history through the schema's foreign-key cascade, and the confirmation must say so.

Search may be client-side over loaded pages for MVP. Label it accordingly; do not imply a complete-history search unless the API implements one safely.

### 4.3 Define tab state and lifecycle

Support SQL editor tabs as the primary tab type. Redis key/value browsing and the command console may remain panels within a Redis workspace unless existing UI architecture naturally models them as tabs.

Each SQL tab owns:

- stable in-process tab ID;
- connection ID and captured environment/read-only display state;
- title, initially derived from the statement or `Untitled`;
- SQL text and CodeMirror editor state;
- dirty/clean flag;
- active query ID and execution state;
- latest result/error for that tab;
- creation and last-activation timestamps.

Behavioral rules:

- New tab inherits the currently selected PostgreSQL connection but shows it explicitly.
- Switching the global connection does not silently retarget an existing tab. Retargeting is an explicit tab action.
- A running query remains associated with its originating tab and connection even when the user switches tabs.
- Closing a dirty tab requires confirmation.
- Closing a tab with a running query offers **Cancel and close** or **Keep open**; do not orphan execution silently.
- When the final tab closes, show a useful new-query empty state.
- Tabs use horizontal overflow or a menu rather than shrinking titles to unusability.
- Duplicate a tab by copying text and connection, without copying a running query ID.

Keep tab contents in process memory for v0.1. Do not persist SQL drafts to `localStorage` without a deliberate security decision because query text can contain sensitive values. Warn before application shutdown or page unload when dirty tabs exist where the platform permits it.

### 4.4 Implement keyboard shortcuts

Required shortcuts:

| Action | macOS | Windows/Linux |
|---|---|---|
| Run selection/current statement | `Cmd+Enter` | `Ctrl+Enter` |
| Open connection switcher | `Cmd+K` | `Ctrl+K` |

Recommended low-risk additions:

| Action | macOS | Windows/Linux |
|---|---|---|
| New SQL tab | `Cmd+T` only if browser handling is safely prevented in app context | `Ctrl+T` with same caveat |
| Close active tab | `Cmd+W` only with reliable dirty-state handling | `Ctrl+W` with same caveat |
| Cancel active query | `Cmd+.` | `Ctrl+.` |
| Focus schema/key search | `Cmd+Shift+F` | `Ctrl+Shift+F` |
| Show shortcuts | `Cmd+/` | `Ctrl+/` |

The `Cmd/Ctrl+T` and `Cmd/Ctrl+W` caveats exist only because this milestone still runs in a browser tab. [M5](05-desktop-shell.md) removes the browser and revisits both, along with the native menu accelerators — so treat them as provisional here and do not ship the caveat wording in user-facing help text.

Do not intercept shortcuts while a confirmation dialog, dropdown, or text field needs the same keys, except for an explicit global Escape behavior. Respect CodeMirror's own undo, redo, search, and selection keymaps.

Expose shortcuts in button tooltips and a searchable help dialog. Use platform-appropriate labels detected at runtime. Every shortcut action must also be reachable by pointer and assistive technology.

### 4.5 Build the connection switcher

`Cmd/Ctrl+K` opens a focused switcher listing saved connections with:

- name;
- PostgreSQL/Redis engine;
- environment and color;
- read-only status;
- open/closed/error state;
- fuzzy or substring name search over the small local list.

Selecting a connection:

- opens it if needed, with progress and safe error handling;
- changes the active workspace for new work;
- does not retarget existing tabs silently;
- moves focus to the most relevant editor/browser after success.

Production connections must keep their red `PROD` label inside the switcher and after selection. Color alone is insufficient.

### 4.6 Standardize error presentation

Create one normalized frontend error model matching the backend envelope. Decide presentation by scope:

- **Field error:** invalid form input, attached to the field.
- **Inline panel error:** schema node, result tab, Redis value, dashboard section.
- **Banner:** locked state, disconnected connection, production/read-only context, global server issue.
- **Toast:** transient success or non-blocking notification; never the only home for an actionable error.
- **Fatal boundary:** unexpected React render failure, with a safe reload/recovery action.

Every error should answer what failed, whether work/data was changed, and what the user can do next. Avoid raw driver text when a stable explanation exists, but preserve useful PostgreSQL SQLSTATE, detail, and hint fields from M2.

Complete PostgreSQL position UX:

- highlight the exact editor position/range;
- scroll it into view;
- attach the primary message near the editor or result panel;
- clear stale highlighting on edit or next execution;
- keep the raw submitted statement offsets so tab edits made while a query ran do not move the marker incorrectly—if the text changed, show the error without a misleading location.

Redis partial failures remain local: missing memory info does not become a key-browser failure, and an expired key does not disconnect the workspace.

### 4.7 Design loading and empty states

Every asynchronous surface needs initial loading, refreshing, empty, failure, and success-with-no-items states.

Required empty states:

- no saved connections: explain PostgreSQL/Redis support and offer **Add connection**;
- locked store: explain why the master password is needed;
- no SQL tabs: offer **New query** and recent history;
- empty schema: distinguish no objects from insufficient permissions;
- query returned zero rows: show successful duration and column headers if available;
- no Redis scan matches: show pattern/type, traversal completion, and **Clear filters**;
- empty Redis collection: show that the key exists but has no entries where the type permits it;
- no query history: explain that executed queries will appear locally;
- disconnected/error connection: show **Reconnect** without discarding editor text.

Use skeletons only when they resemble the arriving layout. Use compact spinners/progress labels for actions. Never leave a disabled control without explaining the active operation.

### 4.8 Add theme tokens and dark mode

Implement light, dark, and system preference with CSS custom properties/design tokens rather than component-specific color patches.

Token categories should cover:

- application surfaces and elevated panels;
- primary/secondary text;
- borders, focus rings, selection, and hover;
- success, warning, error, production, and read-only status;
- editor syntax and active-line colors;
- grid header, alternating row if used, NULL, and selected cell;
- Redis type badges and connection colors with adequate contrast.

Persist only the theme preference locally; it is non-sensitive. Apply the preferred theme before first paint to avoid a bright flash. Test native controls, scrollbars where styleable, CodeMirror, grid overlays, dialogs, JSON views, and focus indicators in both themes.

Do not use low-contrast gray text as the only difference for NULL or disabled state. Meet WCAG AA contrast for normal text and visible keyboard focus where practical.

### 4.9 Accessibility and interaction pass

- Use semantic buttons, forms, labels, dialogs, tabs, and tree roles.
- Make tab and tree navigation keyboard-operable with expected arrow-key behavior.
- Trap focus inside modal dialogs and restore it to the invoking control on close.
- Announce query completion, cancellation, and important errors through an appropriate live region without reading entire result sets.
- Give icon-only actions accessible names and visible tooltips.
- Ensure virtualized grids retain understandable row/column context for assistive technology; provide an alternate expanded row/value representation if needed.
- Respect reduced-motion preference.
- Verify 200% zoom and common laptop viewport sizes without hiding run/cancel/environment context.

### 4.10 Performance and reliability pass

Exercise normal and intentionally awkward workloads:

- 100+ schemas/tables expanded over time;
- 1,000 rows with 100 columns;
- large JSON/text previews at the response cap;
- many Redis scan pages and prefix nodes;
- 50+ open history entries and 20 tabs;
- rapid tab/connection switches while a query is running;
- backend restart while the SPA remains open;
- expired session token/process replacement.

Look for listener leaks, stale requests updating the wrong tab, excess re-rendering, retained large results, and unbounded caches. Cancel obsolete frontend requests with `AbortController`; backend contexts should end when clients disconnect.

Keep at most a bounded number/size of result models in memory. If evicting an old result, preserve its query text and show that the result must be rerun.

## Testing

### Automated

- History keyset pagination, filters, deletion scope, and sensitive-data handling
- History open/rerun behavior and production-connection safeguards
- Tab create, switch, retarget, duplicate, close-dirty, and close-running flows
- Query completion routed to the originating background tab
- Keyboard shortcuts by platform and focus context
- Connection-switch behavior and failure recovery
- Normalized error routing and stale PostgreSQL marker prevention
- Every required empty/loading/error state
- Theme preference and system-theme changes
- Focus restoration, accessible names, and core keyboard navigation
- Bounded in-memory result/history/tab behavior

### Manual acceptance scenario

1. Use keyboard only to unlock, open a connection, create a SQL tab, run a query, inspect its status, and open history.
2. Open several tabs on different environments and confirm each retains its connection context.
3. Start a long query, switch tabs, cancel it, and confirm the right tab receives the result.
4. Attempt to close dirty and running tabs and verify the appropriate choices.
5. Use `Cmd/Ctrl+K` to switch between PostgreSQL and Redis connections.
6. Reopen a query from history and verify it does not execute automatically.
7. Trigger representative form, PostgreSQL, Redis partial, disconnected, and unexpected UI errors.
8. Check all main screens in light and dark themes at 200% zoom.
9. Navigate tabs, dialogs, schema tree, key browser, and main actions with a keyboard and a screen-reader smoke test.
10. Work normally for several hours and record every moment another tool feels easier.

## Deviations

Recorded as the milestone is implemented, per the process in [`README.md`](README.md).

### 4.1 and 4.2 — query history

**There is no HTTP API, so there are no endpoints.** The stack move removed the
loopback server; `GET /api/history` is `ConnectionService.history(HistoryQuery)` and
`DELETE /api/history` is `ConnectionService.clearHistory(HistoryScope)`, both
`suspend fun`s called in-process. The Origin check has nothing to check. What
survived the translation intact is everything the section was actually about: a
bounded default page, a hard ceiling, keyset pagination, an opaque cursor, an
optional connection and status filter, and a deletion whose scope is explicit.

**The keyset is `id`, not `(executed_at, id)`.** The column holds
`Instant.toString()`, whose fractional second is written only when there is one — so
under the text comparison SQLite applies, `…09:00:00.500Z` sorts *before*
`…09:00:00Z`. A clock stepped backwards by NTP is the second reason.
`id` is `INTEGER PRIMARY KEY AUTOINCREMENT`, assigned in write order, and present on
every row. Migration 3 replaces the index that ordered by time with one that orders
by it. The retention prune was reading the same broken order and now reads the new
one.

**The read does not resolve the connection's display information.** The guide asks
for it because a browser SPA cannot join against a table it has no copy of. The
panel here is handed the connection list the workspace already holds, so it reads the
name, colour, and environment live — which is *more* current than a value copied into
the response, and one less projection type in `:core`. The cascade still guarantees
every history row has a connection to look up.

**The cursor is opaque by visibility rather than by encoding.** `HistoryCursor`
wraps a row id whose field is `internal`, so `:app` can carry one from a page back
into the next request and cannot construct one. `ExecutionRecord.cursor()` is the
only other way to obtain one, and it can only name a position the caller has already
been handed a row for.

**History is a window, not a docked panel.** It is the one surface that spans
connections — the tab strip belongs to one open server — and it follows the
convention the previous commit established for everything that is a thing you go to
and come back from. It opens filtered to the selected connection.

**Reopening targets the editor, not a new tab, until 4.3 builds tabs.** The rule the
section actually cares about is kept in full: opening never executes, and replacing a
script that holds work asks first. The action is offered only for the connection the
editor is pointed at; an entry from another server shows a disabled button naming the
connection that would have to be open, because the alternative is a history click
that dials production.

**Search is client-side over the loaded pages,** which the section permits, and the
box says so beside itself rather than in a tooltip.

**Reopening in a new tab arrived with 4.3,** as that entry said it would. Both of
§4.2's actions are now offered on every entry: `Open in new tab`, which can lose
nothing and is therefore the primary one, and `Open in this tab`, which is the
replacement that asks first.

### 4.3 — query tabs

**The strip belongs to a connection.** `EditorTabs.of(id)` answers with the tabs
pointed at one server and the query pane draws those. It is the one arrangement in
which the shell bar above the strip, the object browser beside it, and the tab in
front of the user all describe the same server — a single strip spanning connections
would put a tab that runs against production under a shell that is not red, and this
application spent M2 making that bar impossible to miss.

What the section is actually about survives intact. A tab captures its connection when
it is opened and never changes it silently; selecting another connection in the
sidebar shows *that* connection's tabs rather than retargeting the one on screen; and
`Retarget` is `EditorTabs.retarget`, offered as `Move to …` in the tab's own menu, for
open PostgreSQL connections only — a menu entry that first opens a connection is a
menu entry that dials production. Moving takes the workspace with it, because a tab
that vanished from the strip it was in would look closed. It is refused while that tab
has a statement running: the query belongs to the server it was sent to.

**Because of that, a tab does not repeat its connection on itself.** The section asks
for a new tab to show its connection explicitly, and here the shell bar directly above
the strip is that, always, for every tab in it. A chip inside the editor toolbar would
be the same fact drawn twice.

**A tab is an `EditorViewModel` plus an identity.** The editor already owned a
connection, a script, a running query, and one result — which is most of the section's
list — so `EditorTab` adds the rest: an opaque `TabId` that is never reused, a title,
and the export. The export is per tab rather than per window because §2.10 makes it a
second execution of *this* tab's statement; one shared between tabs would report a
file written from a query the user has since switched away from.

**Timestamps are a counter, not a clock.** The section asks for creation and
last-activation times. Creation order is the list order, and activation is a monotonic
`Long`, for the same reason 4.1's keyset is a row id: wall time steps backwards under
NTP, and "the tab I was last in" then resolves to one nobody has touched in an hour.
Nothing in the UI shows either as a time, so nothing was lost by not storing one.

**Dirty means "holds something that exists nowhere else",** not "has been edited". The
editor keeps a baseline — empty for a tab opened blank, the statement itself for one
opened from history — and a tab is dirty when its text is neither blank nor that. So
closing a reopened statement that has not been touched asks nothing, and closing a
duplicate always does: a copy is a second thing to lose.

**Closing asks about the running statement first, and names the script in the same
breath.** A tab can be both; a dialog that said only "a query is running" and then also
threw away twenty lines of SQL would have lied by omission.

**Titles are derived live rather than fixed at creation,** which is what makes a strip
of six readable without a rename command — the first line a person writes is the verb
and the table. There is no rename, and no `Close others`.

**The first tab is given, the last one is not taken back.** Opening a connection lands
in an editor exactly as it did before there were tabs, but only the first time in a
session: someone who closed the last tab asked for the empty state, and reselecting
the connection is not a request to undo that.

**Deleting a connection takes its tabs without a second question,** which the delete
confirmation already covers — there would be nowhere left to run what is in them.
Closing a connection keeps them, and they are there when it is opened again.

**Shutdown is guarded by `ExitGuard`,** which is a plain class rather than anything
composable: `onCloseRequest` arrives from AWT at a moment that is nobody's
recomposition. The workspace registers the question, the window asks it, and asking
twice does not stack two dialogs.

### 4.4 — keyboard shortcuts

**The command key is the platform's, and only the platform's.** The editor's Run chord
accepted Meta *or* Control on both platforms since M2, and that rule does not survive
being generalised to seven chords. Compose's own macOS text-field keymap binds
`Ctrl+K` to delete-to-line-end and `Ctrl+Shift+F` to extend-selection — emacs bindings
AppKit has carried for decades — so accepting Control as a command modifier on macOS
would put the connection switcher and the search chord on keys the text field under
them has already spoken for, and the user would get whichever won, differently
depending on where the caret was. One rule instead: Meta on macOS, Control everywhere
else, matched and drawn from the same table.

**The `Cmd/Ctrl+T` and `Cmd/Ctrl+W` caveats do not apply and have not been carried
forward.** The section marks both provisional because the milestone "still runs in a
browser tab" and points at M5 to revisit them. The stack move removed the browser
before M0 shipped, and there is no M5 to revisit anything — this is a Compose window,
`⌘T` and `⌘W` reach it, and nothing else wants them. Both are ordinary shortcuts here.

**`Cmd/Ctrl+Shift+F` focuses the key search only.** The schema tree has no search box
to focus. Adding one is not in this milestone's included scope, and a chord that
sometimes does nothing is worse than one that is honestly described, so the reference
window names it "Focus the key search" and says it is for Redis connections.

**Nothing is registered while a dialog is open, and the list of dialogs is written out
rather than inferred.** Compose renders a `Dialog` into a layer of its own, and whether
a key event bubbles past it is an implementation detail this application should not be
betting a `DELETE` on. The workspace enumerates every window and confirmation it can
raise and declines every chord while one is up; the quit confirmation is checked in
`Main`, because it is the one modal the workspace underneath cannot see.

**The binding is dropped when the workspace leaves the composition.** Locking replaces
the screen and keeps the window, and a handler that outlived it would put a connection
switcher over the lock screen — the one surface in the application that must show
nothing.

**One table, read by everything.** `Shortcut` carries the key, the words, the group,
and how the chord is spelled on each platform. The Run button's label, the tooltips,
and the reference window all read it rather than restating it, because a help window
is the one screen that can be wrong for a year without anyone noticing.

### 4.5 — connection switcher

**It is a palette, not an `AppDialog`.** Every other window in this application is a
place you go — history, settings, the connection form — and is shaped like one. This is
a chord, three letters, and Enter, and it is over in a second.

**Enter on a freshly opened switcher never lands on a production connection.** The
saved list is ordered production first, deliberately, so that in a list you *read* the
dangerous servers are never buried. This is a control you *act* in, where the identical
ordering makes the default gesture dial production. The list order is kept — it is the
sidebar's, and two orders would be one for the user to learn — and the preselection
skips the red rows instead. Typing a name overrides it, and so does arrowing onto one:
both are the user saying which server they mean. When every match is production nothing
is preselected, and the footer says to type or use the arrows rather than leaving Enter
looking broken.

**Only the name is matched.** Substring rather than fuzzy, which the section permits,
and over the name alone: a connection surfacing under a word that appears nowhere on
its row reads as a bug, and the fix — printing the host on every row to explain the
match — would cost the switcher the scannability it exists for. A name that begins with
what was typed is promoted above one that merely contains it.

**Focus is left as a request rather than taken.** The section asks that choosing move
focus to the editor or browser it lands in, and the switcher cannot do that itself: it
closes before that pane exists, because the connection may still be being dialled, and
requesting focus on an unattached requester throws. `FocusRequest` is raised by the
switcher and consumed by whichever pane turns up — once, so returning to a tab later
does not pull the caret out of wherever the user has since put it.

### 4.6 — error presentation

**The normalized model was already there, and it is `Failure`.** The section asks for
a frontend error model matching the backend envelope; there is no envelope and no
frontend, so `:core` throws and `Throwable.toFailure()` classifies. Every surface
renders the same `Failure` — code, message, and the structured server report when
there is one — through the same `ErrorBanner`. What M4 added is the one field the
banner had no way to carry: a note the *application* is making about the error, as
opposed to something the server said, set in italic below the report.

**Presentation by scope was settled surface by surface as each was built,** and the
section's five levels map onto four. A field error is `Failure.fields`, drawn on the
input. An inline panel error is the banner inside the pane that failed — the schema
node, the result area, the Redis value viewer, the `INFO` dashboard, the key browser.
A banner is the workspace-level one above the working area, dismissible, for a
connection that would not open. There are no toasts: nothing in this application
reports success transiently, and the section's own rule is that a toast must never be
an actionable error's only home.

**There is no fatal boundary, because Compose Desktop has no equivalent to one.** A
React error boundary catches a render failure in a subtree and swaps in a fallback;
a composition that throws here takes the window with it, and there is no supported
way to isolate a subtree. What exists instead is the one failure that can happen
before there is a UI to fail in: `Startup.Failed` renders the vault-unavailable
screen rather than a stack trace on a terminal nobody is watching.

**A highlight is drawn only while the script still reads the way it was sent.**
This is the section's hardest requirement and the reason it was left until last. The
server counts its error position into the statement it received, `Statement.documentIndex`
maps that onto the document, and both are computed the moment the failure arrives —
while the statement that was sent is still in hand. But the editor stays usable while
a query is on the server, so by the time an error comes back those offsets may
describe characters the user typed after pressing Run.

`Statement.isIntactIn(document)` is the check: the submitted text, compared against
the same span of the script as it reads now. `EditorViewModel.marker` answers with
one of three things — nowhere to point, a place, or *there was a place and it is
gone* — and both the underline in the editor and the sentence under the result read
that one property, so they cannot disagree. An edit below the failed statement leaves
the marker alone, which is the case worth keeping: a failure in statement two stays
marked while statement three is being written. An edit above it or inside it removes
it, and the banner says why. Underlining an innocent word is worse than underlining
nothing, because the user has no way to tell a stale marker from a correct one.

**A position one past the last character is scrolled to and not underlined.**
PostgreSQL reports an error at end of input that way. There is no character there;
marking the one before it would be pointing at the wrong thing.

**Redis partial failures were already local** and needed nothing here. A restricted
`INFO` is a success with fewer sections rather than a failed dashboard, and a key that
changed type between being selected and being read reloads the viewer as the type it
now is — `DbError.KeyTypeChanged` carries it. Neither reaches the workspace.

## Completion checklist

- [x] Query history is bounded, paged, filterable, reopenable, and clearable.
- [x] SQL tabs retain their own connection, editor, query, and result state.
- [x] Dirty/running tabs cannot be lost silently.
- [x] Required shortcuts work without breaking editor or dialog behavior.
- [x] Connection switching preserves production/read-only context.
- [x] Errors are normalized and presented at the correct scope.
- [x] PostgreSQL error highlighting is accurate and never stale/misleading.
- [ ] Every primary surface has intentional loading and empty states.
- [ ] Light/dark/system themes cover editor, grid, dialogs, and status colors.
- [ ] Core workflows are keyboard accessible and usable at zoom.
- [ ] Large normal-use states stay responsive and memory-bounded.

## Exit criterion

During ordinary PostgreSQL and Redis use, no interaction, error state, or visual inconsistency makes the application feel like unfinished scaffolding.
