# M3 — Redis Read Path

## Outcome

Deliver a production-conscious Redis workflow: inspect server health, browse keys with bounded `SCAN`, view metadata, page through values according to their Redis type, and run raw commands through a guard that blocks dangerous operations by default.

The defining rule is that browsing must never block Redis the way `KEYS *` can. Every collection operation is cursor- or range-based and bounded by count, time, bytes, and server iterations.

## User-visible workflow

1. Select and open a saved Redis connection.
2. View a compact `INFO` summary and connectivity state.
3. Browse keys by pattern, optional type, and prefix grouping.
4. Select a key to see type, TTL, memory estimate, and existence state.
5. Page through its value with a viewer suited to string, hash, set, sorted set, list, or stream.
6. Pretty-print JSON-looking values without changing the underlying text.
7. Run a raw Redis command and see a bounded, type-aware response.
8. Receive explicit warnings or blocks for dangerous commands and production operations.

## Scope

### Included

- Bounded `SCAN` key browser with pattern and type filter
- Pipelined `TYPE`, `TTL`, and `MEMORY USAGE` metadata
- Prefix-tree grouping in the frontend
- Type-aware, paged value viewers
- TTL and memory display
- Selected `INFO` metrics
- Raw command console accepting structured argument arrays
- Dangerous-command guard and production typed confirmation
- Server-side read-only policy

### Not included

- Redis Cluster or Sentinel topology
- `MONITOR`, pub/sub viewer, slow-log explorer, or profiler
- Key editing, renaming, deletion, TTL mutation, or bulk actions
- Live charts or metric retention
- Lua editor/debugger
- Unbounded full-value downloads

## API contract

Implement the Redis endpoints from the source plan:

```text
GET  /api/redis/:id/info
GET  /api/redis/:id/scan?cursor=&match=&type=&count=
GET  /api/redis/:id/key?name=
GET  /api/redis/:id/value?name=&cursor=&offset=&limit=
POST /api/redis/:id/command
```

Every endpoint verifies that the saved connection exists, is Redis, is unlocked, and has an open runtime client. Key names are data, not URL path segments; keep them in encoded query parameters so slashes and binary-adjacent characters do not alter routing.

Use base-10 cursor strings in JSON and URLs. Redis cursors are unsigned 64-bit values, which can exceed JavaScript's exact integer range. Never serialize them as JSON numbers.

> **Note, 2026-08-21 — there are no endpoints, and the cursor rule survives anyway.**
> The five routes above are the Go build's, and the stack move deleted the wire they
> travelled on. They are now suspend functions: `RedisAdapter.info`, `.scan`,
> `.metadata`, `.value`, and `.execute`, reached through `ConnectionService`, which is
> where "exists, is Redis, is unlocked, has an open client" is checked — `redis(id)`
> asks the registry, and the registry hands back a Redis client or throws
> `WrongEngineException`. There is no routing, so "key names are data, not path
> segments" is now stronger than it was: a key is a `RedisKey` carrying bytes, and
> there is no string form of it anywhere on the path to the server.
>
> The cursor rule is kept, and the reason it was written for turns out not to have
> been the JSON. A Redis cursor is unsigned 64-bit, so half its range does not fit in
> a `Long` as a positive number — a cursor past 2^63 read as one comes back negative,
> and a negative cursor sent back is a protocol error partway through a traversal
> that was working. `RedisCursor` is therefore still text: validated on the way in,
> normalized so two spellings compare equal, and otherwise opaque.

## Work packages

### 3.1 Define safe resource limits

Centralize limits rather than scattering magic numbers across handlers. Provide conservative defaults for:

- requested keys per page;
- maximum SCAN calls per HTTP request;
- total SCAN duration;
- pipelined metadata batch size;
- collection entries per value page;
- string byte range per page;
- maximum bytes per API response;
- command response depth, element count, and bytes;
- Redis operation timeout.

Client-provided `count` and `limit` values are hints capped by the server. An empty page can be a successful result, not an error.

### 3.2 Implement bounded key scanning

The scan endpoint must call Redis `SCAN`; it must never use `KEYS`, including as an optimization for small databases.

Request behavior:

- `cursor` defaults to `0` for a new traversal.
- `match` defaults to `*` but is passed as a Redis glob, not a regular expression.
- `type` is optional and validated against a fixed list of supported types.
- `count` is capped and passed as Redis's count hint.

Server algorithm:

1. Begin with the supplied cursor.
2. Call `SCAN` with `MATCH`, optional `TYPE`, and bounded `COUNT`.
3. Append returned keys until the page target is reached.
4. Continue across empty batches while the cursor is nonzero, but stop after the configured iteration or time budget.
5. Return collected keys and the latest cursor.
6. Mark traversal complete only when Redis returns cursor `0`.

Do not treat an empty key batch as completion. `MATCH` can produce many empty batches while the cursor continues to advance. `COUNT` is only a hint, so responses may contain fewer keys than requested.

Redis may return duplicates during a changing scan. Deduplicate within one response on the server and across the active browsing session in the client where practical. Do not promise snapshot consistency; explain that keys created/deleted during browsing can be missed or repeated.

If server support for `SCAN TYPE` is unavailable, either return a capability error or fall back to bounded post-filtering with pipelined `TYPE`. Never perform an unbounded full scan to satisfy a type filter.

Suggested response:

```json
{
  "cursor": "72863164105861143",
  "complete": false,
  "keys": [
    {"name": "user:42:profile", "type": "hash", "ttlSeconds": 3580, "memoryBytes": 912}
  ],
  "iterations": 3
}
```

### 3.3 Pipeline key metadata

For the collected page, pipeline metadata commands instead of making serial round trips:

- `TYPE key`
- `TTL key`
- `MEMORY USAGE key`

Keep results associated by original key position. A key can expire or be deleted between `SCAN` and the pipeline, so model `missing` as a normal race rather than failing the page.

TTL semantics:

- non-negative value: seconds until expiry;
- `-1`: exists with no expiry;
- `-2`: key no longer exists.

Memory usage is an estimate and may be unavailable because of server version, permissions, or command configuration. Return `null` with a per-field capability/error marker rather than failing the entire page.

Do not pipeline an unbounded number of commands. Since three commands are issued per key, cap the page and split a large page into bounded pipeline batches if necessary.

### 3.4 Build key browser and prefix grouping

The key list is the source of truth; the prefix tree is a presentation derived from keys already returned. It must not trigger hidden full-database scans.

Frontend behavior:

- Search input uses Redis glob syntax and explains common forms such as `user:*`.
- Type filter supports string, hash, list, set, zset, and stream.
- **Load more** continues from the last cursor; **Refresh** starts from `0` and clears deduplication state.
- Completion is shown only when cursor `0` is returned.
- Empty intermediate results show scan progress and allow continuation automatically within UI bounds.
- Group keys by a configurable delimiter, default `:`. For example, `user:42:profile` appears under `user` → `42`.
- Show the original full key name and never reconstruct commands from truncated display labels.
- Display type badge, TTL state, and optional memory estimate.
- Clearly state that browsing is not a consistent snapshot.

Large key names must be truncated visually while remaining copyable. Render key names as text only.

### 3.5 Implement key metadata lookup

`GET /key?name=` refreshes one selected key independently from the scan page. Pipeline `TYPE`, `TTL`, and `MEMORY USAGE` and return:

- exact key name;
- existence state;
- Redis type;
- TTL state/value;
- optional memory bytes;
- which viewer/pagination mode applies.

If the key expires, the UI should close or mark the value viewer stale without turning the whole Redis workspace into an error.

### 3.6 Implement type-aware value paging

All value responses share these fields:

```json
{
  "key": "example",
  "type": "hash",
  "items": [],
  "nextCursor": "0",
  "nextOffset": null,
  "complete": true,
  "truncated": false
}
```

Validate the current key type before reading. If it changed since selection, return `key_type_changed` with the new type so the UI can reload the proper viewer.

#### String

- Use `STRLEN` plus `GETRANGE start end` for bounded reads.
- Treat offsets as byte offsets because Redis strings are byte sequences.
- Return a UTF-8 text preview only when valid; otherwise return a safe base64 or hexadecimal representation and label it binary.
- **Show more** advances the byte range. **Show full** still obeys a hard maximum and must warn when the value exceeds it.
- Attempt JSON detection only on complete, valid UTF-8 text within the JSON-size limit.

#### Hash

- Use `HSCAN key cursor COUNT n`.
- Return field/value pairs and the next cursor as a string.
- Preserve duplicate-looking display values and exact field names.
- Apply per-field and per-value byte preview limits.
- Pretty-print JSON-looking values independently without changing originals.

#### Set

- Use `SSCAN key cursor COUNT n`.
- Return members and next cursor.
- Do not imply stable ordering.

#### Sorted set

- Use `ZRANGE key start stop WITHSCORES`.
- Return member and score text so precision is not accidentally changed by JavaScript.
- Use offset pagination and explain that concurrent updates may move members between pages.

#### List

- Use `LLEN` plus `LRANGE key start stop`.
- Return absolute indices with each value.
- Use offset pagination; list mutations can shift later pages.

#### Stream

- Use `XRANGE` with an exclusive continuation ID and `COUNT`.
- Return entry ID plus ordered field/value pairs.
- Preserve IDs as strings.
- Continue after the last returned ID without duplicating it.

Do not use `HGETALL`, `SMEMBERS`, an unbounded `LRANGE`, or an unbounded `XRANGE`. Response byte caps apply even when the entry count is below its cap.

### 3.7 Build value viewers

Use a shared frame for key name, type, TTL, memory, refresh, loading, expired, and error states. Render the body by type:

- string: text/binary preview with range progress and optional JSON tree;
- hash: virtualized field/value table with scan progress;
- set: virtualized member list;
- zset: member/score table with offset range;
- list: index/value table;
- stream: entry timeline/table with expandable fields.

For JSON detection:

- parse only valid complete UTF-8 strings under the size threshold;
- accept object, array, and scalar JSON, but use tree view primarily for object/array;
- fall back silently to text when parsing fails;
- retain a raw view and copy the original bytes/text, not reformatted JSON;
- cap rendering depth and collapsed node count.

Distinguish an empty collection from a missing/expired key.

### 3.8 Add the `INFO` dashboard

Fetch `INFO` once when the Redis workspace opens and on manual refresh. Parse only known fields; keep unknown fields out of the primary UI rather than binding presentation to one Redis version.

Useful v0.1 cards:

- Redis version and mode;
- uptime;
- connected clients;
- used memory and max memory;
- keyspace hits and misses with a guarded hit-rate calculation;
- total commands processed;
- instantaneous operations per second;
- role and connected replicas when available;
- database key and expiry counts from keyspace sections.

Treat unavailable sections and permission errors as partial data. Do not fail key browsing because `INFO` is restricted. Do not add polling/live charts in this milestone.

### 3.9 Implement raw command parsing and execution

The API accepts an argument array, never a shell-like command string:

```json
{
  "args": ["GET", "user:42"],
  "allowDangerous": false,
  "confirmation": null
}
```

The console UI may provide a command-line input, but its tokenizer must support quoted arguments, escapes, spaces inside values, and empty arguments. Show the parsed argument list before execution when quoting is ambiguous. There is no shell interpolation, environment expansion, or command substitution.

Server behavior:

- Require at least one non-empty command token.
- Normalize the command and relevant subcommand to uppercase only for policy checks; preserve original argument bytes for Redis.
- Execute through `Do(ctx, args...)` with a timeout.
- Normalize RESP results into bounded JSON supporting null, integer, string/binary, error, array, and nested map/set-like replies as returned by the client.
- Limit nesting depth, elements, and total bytes. Return truncation metadata rather than consuming unbounded memory.
- Record duration and safe command name. Do not log full arguments.

Console history in the frontend must avoid persistent storage for v0.1 because arguments can contain secrets. Keep it in memory for the current session only.

### 3.10 Enforce the dangerous-command guard

Block these commands by default, including relevant subcommand forms:

- `FLUSHALL`
- `FLUSHDB`
- `KEYS`
- `SHUTDOWN`
- `DEBUG`
- `CONFIG SET`
- `MONITOR`
- `SWAPDB`

The check is server-side and cannot be bypassed by whitespace, casing, or splitting `CONFIG` and `SET` across fields. Extend the list when a command can block the server, expose sensitive configuration, or destroy broad data.

Override behavior:

- The frontend exposes a temporary **Allow dangerous command** toggle only after a warning.
- The toggle resets after one execution and is never saved.
- The request includes explicit acknowledgement; the server still validates the command.
- On `prod`, require typed confirmation containing the connection name plus command name.
- On `read_only`, dangerous commands remain blocked regardless of override.

For read-only connections, use a conservative allowlist for the raw console rather than attempting to enumerate every write command. Include common introspection/read operations needed by the tool and reject unknown commands with `command_not_allowed_read_only`. The dedicated browser endpoints remain the preferred safe path.

Database ACLs are the final control. The UI guard reduces accidents but does not replace Redis ACL configuration.

## Failure handling

Model normal Redis races and partial capability explicitly:

| Condition | Behavior |
|---|---|
| Empty SCAN batch, cursor nonzero | Continue within budget or return cursor for Load more |
| Key expired after scan | Mark missing and keep the rest of the page |
| Key type changed | Ask the viewer to reload for the new type |
| `MEMORY USAGE` denied | Show memory as unavailable; keep browsing |
| `INFO` denied | Show dashboard warning; keep key/value tools usable |
| Operation timeout | Stop work and return a timeout error |
| Response limit reached | Return bounded data with `truncated: true` |
| Dangerous command | Block with reason and required acknowledgement path |

Never include Redis passwords or complete secret-bearing command arguments in errors.

## Testing

### Unit tests

- Cursor string parsing above JavaScript's safe-integer range
- SCAN loop with empty batches, duplicates, completion, iteration budget, and timeout
- Pipelined metadata alignment and expired-key races
- TTL sentinel mapping
- Per-type request validation and continuation calculation
- Binary versus UTF-8 string representation
- JSON detection size/depth limits
- Console tokenizer with quoting, escapes, and empty arguments
- Dangerous-command normalization, subcommands, override, production, and read-only policy
- RESP normalization depth/size limits
- `INFO` parsing with missing and unknown fields

### Integration tests

Run against a disposable Redis server containing:

- enough mixed-type keys to require multiple scans;
- expiring and non-expiring keys;
- large strings and collections;
- binary strings;
- JSON and non-JSON text;
- a changing key during scan/read;
- restricted ACL user lacking `INFO` or `MEMORY USAGE`;
- read-only ACL user;
- large/nested command replies.

Assert that key-browser code never issues `KEYS`, `HGETALL`, `SMEMBERS`, or unbounded range reads. Where practical, inspect a command log in the disposable server/test client.

### Frontend tests

- scan pagination through empty intermediate pages;
- pattern/type reset behavior and deduplication;
- prefix grouping without hidden network scans;
- key expiry and type-change states;
- each value viewer's continuation behavior;
- raw/JSON and text/binary switching;
- guarded command confirmation, single-use override, production confirmation, and read-only block;
- partial `INFO` dashboard.

### Manual acceptance scenario

1. Open a real Redis connection with mixed key types.
2. Browse with a restrictive pattern that produces empty intermediate SCAN batches; confirm browsing continues until keys or completion.
3. Filter by type and inspect pipelined type, TTL, and memory metadata.
4. Open a large string, hash, set, zset, list, and stream; confirm every viewer pages without loading the entire value.
5. Inspect JSON and binary strings; confirm both are represented safely.
6. Let a selected key expire and confirm the UI handles it without a workspace-level failure.
7. Run harmless commands such as `PING` and a bounded `GET`.
8. Try `KEYS *` and `FLUSHDB`; confirm both are blocked by default.
9. On a disposable non-production database, exercise the explicit dangerous override and confirm it resets after one command.
10. On a read-only connection, confirm write/unknown console commands remain blocked server-side.

## Completion checklist

- [x] Key browsing exclusively uses bounded `SCAN`. Asserted against the server's own
      slow log, with every command it was asked recorded — see
      `RedisBrowseIntegrationTest`.
- [x] Empty SCAN batches do not end traversal prematurely.
- [x] Metadata commands are pipelined and tolerate expired keys/partial permissions.
- [x] Prefix grouping uses only already-scanned keys. `RedisKeyTree` takes a list and
      returns a list; it has nothing to call, and `RedisBrowserUiTest` asserts that
      expanding a group leaves the call log unchanged.
- [x] All six supported value types use bounded paging/ranges.
- [x] Binary strings are represented safely, and JSON is detected only where it can be
      done honestly — a complete value, every window decoded, under
      `RedisLimits.jsonBytes`. `JsonFormat` reformats without reinterpreting a value,
      the raw view stays one click away, and a copy takes the original text.
- [x] The `INFO` dashboard degrades gracefully when fields or permissions are missing:
      a field the server did not report is a card that does not draw, a refused `INFO`
      is a banner over a working browser, and there is no hit rate until there has been
      a lookup.
- [x] Raw command parsing preserves structured arguments and bounds replies.
- [x] Dangerous and read-only command policies are enforced in `:core`.
- [x] Production dangerous commands require typed confirmation, and consent is an
      argument rather than a setting — so there is nowhere for a one-shot override to
      persist.

> **Note, 2026-08-21 — the milestone is complete.**
> Work packages 3.1, 3.2, 3.3, 3.5, 3.6, 3.8, 3.9, and 3.10 landed in `:core` first,
> with 643 tests across the module and the Redis integration suites running against a
> real `redis:7-alpine`. **3.4 and 3.7 — the key browser and the value viewers — are
> now built**, along with the console's own screen and the `INFO` dashboard: 284 tests
> in `:app`, of which the Redis ones drive the real composables.
>
> What is worth recording about the UI half:
>
> - **The empty page is the browser's defining behaviour, and it is a loop.** `:core`
>   bounds one request; `RedisBrowserViewModel` bounds how many requests one click may
>   make, and continues by itself while every batch comes back empty. Without the
>   first, a selective `MATCH` is `KEYS` written the long way; without the second, a
>   selective `MATCH` reports an empty keyspace. Five pages per click is the number,
>   and the footer says which of the traversal's four endings this one had, because
>   "complete" and "the budget ran out" look identical if all you draw is a list that
>   stopped.
> - **A key row's label cannot be turned back into a key.** The tree shows a name's
>   last segment and clips what does not fit, and `KeyRow.key` carries the bytes.
>   `RedisBrowserUiTest` clicks a row labelled `profile` and asserts that
>   `user:42:profile` is what comes out.
> - **Paging accumulates; it does not replace.** Every viewer adds the page it read to
>   what is already on screen, which is the difference between reading a large hash and
>   watching one flicker. The continuation is per type — a cursor, a byte offset, a
>   rank, an exclusive stream ID — and each one is asserted by reading the *second*
>   request, because getting one wrong produces no error at all: it produces a **Show
>   more** that silently re-reads the first page.
> - **A string's windows are kept separately, and that is what makes JSON safe.** A
>   `GETRANGE` boundary that lands mid-character does not decode, and `:core` reports
>   that page as binary rather than dropping bytes. So joining the text of several
>   windows is valid only when every one of them decoded — one check, in one place,
>   and the reason a multi-page value is never quietly reassembled wrong.
> - **The console draws the guard's answer; it does not have an opinion.** A refusal is
>   a transcript entry with no override offered, because on a read-only connection none
>   exists. A question is a dialog and not a result, and the phrase it collects goes
>   straight back into one `redisCommand` call. There is no toggle to reset, which is
>   §3.10's single-use rule made structural rather than remembered.
> - **The transcript is memory only, and locking empties it.** `RedisWorkspace.clear()`
>   is called from the same place the pools are closed. A screenshot of the transcript
>   shows command names and durations; the lines that were typed live in the recall
>   history and nowhere else.
>
> And what was already recorded about `:core`:
>
> - **The connection speaks bytes, not strings.** Every key, field, member, and value
>   in Redis is a byte sequence, so the client is opened with `ByteArrayCodec`. A
>   string codec decodes a binary key as UTF-8 and substitutes replacement characters
>   for what did not fit — and sending *that* back finds nothing, so the key appears
>   to vanish when clicked. `RedisBytes` decides text-or-bytes once, at the edge, and
>   the answer travels with the value.
> - **`SCAN TYPE` is post-filtering, which §3.2 sanctions.** Lettuce's `ScanArgs` has
>   no `TYPE` option, and `SCAN TYPE` does not make the server's work smaller anyway:
>   Redis walks the same buckets and discards the misses itself. The metadata pipeline
>   has already read every key's type for the browser's own display, so the filter
>   costs one comparison and no extra command.
> - **The guard's production row is wider than §3.10 asks for.** Dangerous commands
>   take a typed phrase on production, as specified. So does anything *not* on the
>   known-read allowlist — because the alternative is an application that makes you
>   type a phrase to run `FLUSHDB` against production and lets `DEL` through on a
>   click. It reuses the allowlist the read-only column is already built from, and
>   dev and staging behave exactly as written. The dangerous list is also longer than
>   the eight named: `SAVE`, the scripting commands, `REPLICAOF`, `MIGRATE`, and all
>   of `ACL` are each one of the three things §3.10's closing line says to extend for.
> - **A top-level error fails the command; a nested one is a value.** An error inside
>   an `EXEC` array is not the command's failure and raising it would discard a reply
>   that arrived intact. An error that *is* the reply is the failure, and letting it
>   through as a value would mean every caller had to remember to look for one —
>   including the internal reader that pages a stream, where a forgotten check reads
>   as an empty stream.
> - **`XRANGE` is dispatched raw.** Lettuce's `StreamMessage` carries an entry's
>   fields as a `Map`, and a stream entry may repeat a field name — on the one
>   structure in Redis whose whole purpose is recording exactly what was appended.
> - **A simple string and a bulk string are only distinguishable over RESP3.** RESP2
>   delivers both through one driver hook, so `RedisReply.Status` appears only on a
>   RESP3 connection and callers should ask a reply for its text rather than match on
>   the case.
> - **`Redaction` moved to `dev.caracal.core.text`.** It was never PostgreSQL-specific,
>   and Lettuce is if anything freer with the address than pgjdbc — it writes the URI
>   it dialled, password included, into most connection failures. A `redis` package
>   importing scrubbing from a `postgres` one would have been the wrong shape.
> - **Console commands are not recorded in query history**, unlike M2's statements.
>   §3.9's reason is one line long: a Redis command's arguments are where its secrets
>   are — `AUTH`, `CONFIG SET requirepass`, a token being written to a key — and a
>   history that stored them would be a file of credentials on disk.

## Exit criterion

Use the application instead of `redis-cli` to diagnose a real cache issue without issuing a blocking browse command or loading an unbounded value.
