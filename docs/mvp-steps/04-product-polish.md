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

## Completion checklist

- [ ] Query history is bounded, paged, filterable, reopenable, and clearable.
- [ ] SQL tabs retain their own connection, editor, query, and result state.
- [ ] Dirty/running tabs cannot be lost silently.
- [ ] Required shortcuts work without breaking editor or dialog behavior.
- [ ] Connection switching preserves production/read-only context.
- [ ] Errors are normalized and presented at the correct scope.
- [ ] PostgreSQL error highlighting is accurate and never stale/misleading.
- [ ] Every primary surface has intentional loading and empty states.
- [ ] Light/dark/system themes cover editor, grid, dialogs, and status colors.
- [ ] Core workflows are keyboard accessible and usable at zoom.
- [ ] Large normal-use states stay responsive and memory-bounded.

## Exit criterion

During ordinary PostgreSQL and Redis use, no interaction, error state, or visual inconsistency makes the application feel like unfinished scaffolding.
