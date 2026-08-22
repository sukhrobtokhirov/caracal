# Changelog

All notable changes to Caracal are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — while the major
version is 0, a minor bump may change behaviour.

## [Unreleased]

### Added

- **A database engine can be installed rather than built in.** An engine is a module
  with one line in `META-INF/services/dev.caracal.engine.api.DatabaseEngine`; it is
  found at startup, offered in the connection dialog, listed in the settings window,
  and given a generic mark where the application has no artwork for it. Nothing in the
  application names it. A test-only engine proves that: it opens a file rather than
  dialing a host, declares no password, and appears in the dialog with its own fields,
  its own labels and its own validation messages, with no change to any of the code
  that draws them.

### Changed

- **The connection dialog is drawn from the engine's declaration.** Which fields a
  connection has, what they are called, which are optional, which numbers are in
  range, and which transport modes exist are all answers the engine gives. Two
  consequences are visible: a new PostgreSQL connection now opens with `localhost`
  already in the host field, and a Redis connection's database field is labelled
  `Database index` rather than `Database`.
- **A saved connection now records what it points at**, which is a host and a port
  today and can be a file. It also records whatever fields its engine declared, under
  that engine's own names — so an engine can ask for something this application has
  never heard of and have it stored, edited and dialled with. Existing connections
  migrate on first open; nothing about them changes.
- **A connection whose engine is missing no longer breaks the list.** It appears with
  what it points at, says which engine it wants, and refuses to open. Before, one
  unrecognised engine name made the whole configuration file unreadable.
- **A PostgreSQL connection requires a user name.** The driver has always sent one;
  the form now says so before saving rather than letting the server refuse it.
- **The connection test dials through the engine** instead of through a second,
  parallel implementation per engine. Same timeout, same classified errors, one path.
- **A saved credential now records what kind of credential it is.** The vault stored
  a password and the application worked out the rest from the connection's fields;
  it now stores the credential's shape as well, so an engine that authenticates with
  a client certificate, a connection string or an expiring token can say so. Records
  written by v0.1.0 are read as they are, rewritten to the new format the first time
  the vault is unlocked, and readable throughout. Nothing about an existing
  connection changes, and no re-entry of a password is needed.

**Internal — the seam a second wave of engines will arrive through.** No behaviour
changes; every one of these is a refactor covered by the existing tests, including
the ones that run against real PostgreSQL and Redis servers.

- Engines are discovered with `ServiceLoader` rather than listed in a `when`. The two
  bundled ones became classes to be constructible by it, and everything that used to
  ask "which engine is this" now asks the engine.
- The stored connection widened to the shape the SPI dials from: an engine identifier,
  a target, and the engine's declared settings. Schema 4 adds a `connection_settings`
  table and moves the user name and TLS mode into it, keeping both readable by the
  connection they belong to.
- What the stored `require` TLS mode means is read off the engine's own declaration —
  an engine that also offers `verify-full` is drawing PostgreSQL's distinction — rather
  than off its name. Both connections behave exactly as before.
- A new `:engine-api` module holds the service-provider interface: `DatabaseEngine`,
  `DatabaseSession`, `EngineCapabilities`, and the facets a session provides —
  `QueryFacet` and `CatalogFacet` for SQL engines, `KeyValueFacet`, `CommandFacet`
  and `MetricsFacet` for key-value ones. It depends on nothing but Kotlin and
  coroutines, and its build fails if that ever stops being true.
- The window no longer compiles against a database driver. It reads capabilities and
  facets instead: which panes an open connection has, which one the keyboard lands
  in, and what the settings window says about an engine are all answers the engine
  gives rather than a list kept in the UI. A test enforces it.
- Connections are dialled through the engine rather than through a `when`, and the
  connection registry hands out one kind of session instead of one per engine.
- A sealed credential's plaintext is a versioned record rather than the password's
  bytes: a version byte, a kind, and length-prefixed fields. It is bytes and not JSON
  on purpose — a JSON decode puts every password into an immutable `String` that no
  code can wipe, which is the one thing the vault exists to avoid.
- Migration runs on unlock, one record at a time, and skips a record already in the
  current format. A run that is interrupted leaves a file in which some records are
  in the old format and some in the new, every one of them still openable, and the
  next unlock finishes the rest. A credential that will not decrypt at all is stepped
  over rather than deleted, and never keeps the user out of the other connections.
- A configuration file written by v0.1.0 is checked in as a test fixture — a real
  schema-3 database with real sealed records — and is never regenerated. Its digest
  is asserted, so replacing it has to be a deliberate, visible change.
- The vault takes its sealed credentials through an interface of its own, separate
  from the metadata one, so nothing that only needs to verify a password can reach a
  saved credential.

- The stored `require` TLS mode is now translated per engine on the way to a driver,
  because it means two different things: PostgreSQL encrypts without checking the
  server's certificate, Redis checks it. Both connections behave exactly as before;
  what changed is that the difference is written down and tested rather than implied
  by two separate code paths. A Redis connection asking for `verify-full` — which the
  forms have never been able to save — is still refused rather than quietly
  downgraded.

### Fixed

**Correctness**

- A write whose `COMMIT` failed — a deferred constraint, a serialization failure, a
  connection lost between the statement and the commit — reported "rows affected"
  and recorded success in history for a transaction the server had thrown away. The
  commit result is no longer discarded.
- A statement that failed near its timeout was reported as a timeout, losing the
  SQLSTATE, the server's message, the hint, and the position the editor underlines.
  A timeout now also requires the cancellation the server actually performed.
- A saturated connection pool was reported as a failed query, with pool internals in
  the message; it is now a connection failure.
- `SELECT … FOR SHARE` and `FOR KEY SHARE` classified as reads, so they ran with no
  confirmation and were then refused by the server.
- A Redis value window cut through a multi-byte character was shown as hexadecimal,
  so any long non-ASCII string or JSON document rendered as bytes.
- A Redis stream page that reached the reply budget declared the stream complete and
  offered no continuation, making the rest of it unreachable.
- The reply-size budget never truncated anything: the element count was enforced but
  the byte bound was not.
- A `bytea` cell over about a gigabyte overflowed its buffer size and escaped the
  adapter's error handling as "something went wrong".
- A corrupted sealed credential naming a field length near `Int.MAX_VALUE` could
  reach the allocator: the bounds check overflowed to a negative and passed. A
  nine-byte record could ask for two gigabytes, and the unlock path answered with an
  `OutOfMemoryError` rather than a message about a damaged file. Found by the test
  that was written for it.
- Brackets inside a quoted identifier took part in bracket matching, and matching
  scanned past the highlighter's limit, costing a full-document pass per keystroke.
- Two Redis keys sharing a clipped display path crashed the key browser.

**Safety**

- Choosing a master password over a vault whose verifier is missing but whose salt
  is not would have silently re-keyed the vault, making every saved credential
  permanently unopenable. It is refused when sealed credentials exist, and allowed
  when an interrupted first run left nothing to lose.
- A corrupted verifier was reported as a wrong master password and counted against
  the unlock cooldown, so a correct password looked wrong forever.
- Two concurrent first-run setups could store one call's salt beside the other's
  verifier, leaving a vault no password opens.
- A one-character password was exempt from redaction and could reach a log or the
  UI verbatim. The length floor now applies only to connection identity.
- A CSV cell beginning `=`, `+`, `-`, or `@` is neutralized, so a value stored by
  someone else is not executed when the export is opened in a spreadsheet.
- Commands that take over the one shared Redis connection — `SUBSCRIBE`, `SELECT`,
  `WAIT`, `CLIENT REPLY`, the blocking `B*` family, and the replication commands —
  now ask first. They previously ran unprompted and left every later read on that
  connection timing out.
- Re-labelling an open connection as production left it guarded as development.
- An interrupted rename of a pre-Caracal data directory made the whole vault look
  like a fresh installation on the next launch.
- A migration that rebuilds a table no longer cascades the query history away.
- Stored key-derivation parameters are bounded above, so a tampered file cannot turn
  every launch into an out-of-memory error.

**Lifecycle and responsiveness**

- Closing the window while a connection was dialing deadlocked the event thread
  against the connection registry and the process never exited.
- Disconnecting a connection tore its pool out from under a running query and a
  running export; the tab's work is now stopped first.
- Collapsing a schema node cancelled nothing, leaking four catalog reads per schema.
- A single unguarded view-model failure could cancel the window's shared scope,
  silently turning every button into a no-op.
- A close-tab question left behind by a closing connection disabled every keyboard
  shortcut for the rest of the session, and could reappear over another connection.
- Large Redis values and large grid selections were rebuilt on every recomposition,
  freezing the window; ⌘C in the grid swallowed the chord from the text panes below.
- Testing a Redis connection blocked the UI thread on Netty's shutdown.
- The configuration store could be leaked if the window closed while it was opening.

## [0.1.0] — unreleased

The first public release: one window holding PostgreSQL and Redis side by side.

### Added

**Connections**

- A master password, stretched with Argon2id, sealing every saved credential with
  AES-256-GCM. Connections survive a restart and reconnect on unlock.
- PostgreSQL and Redis connections, tagged by environment, with a test button that
  says what failed rather than that something did.
- Read-only by default. A connection can be marked writable; a write is then
  confirmed, and on a production-tagged connection confirmed by typing its name.

**PostgreSQL**

- A schema browser loaded lazily from `pg_catalog`.
- A SQL editor with statement splitting, execute and cancel, and error reporting
  that underlines the character the server pointed at.
- A virtualized result grid that keeps `numeric` and `int8` at full precision.
- CSV export of a result, streamed rather than buffered.

**Redis**

- A bounded `SCAN` key browser with prefix grouping, pattern and type filters, and
  pipelined metadata. Never `KEYS`.
- Paged viewers for all six value types, with TTL, and an `INFO` dashboard.
- A command console behind a guard that refuses the commands that stop a server.

**The application around them**

- Query history: searchable, paged, and reopenable into the editor without running
  anything.
- SQL tabs that keep their own connection, query, and result, and that will not
  close on unsaved work or a running statement without asking.
- Keyboard access throughout, including a connection switcher that will not let
  Enter dial production.
- Light, dark, and system themes, held to WCAG AA contrast by a test that does the
  arithmetic.
- Loading, empty, and disconnected states that say what they are waiting for and
  how to fill them.
- Version, commit, and build date from `--version` and from Settings → About.

### Security

- Nothing leaves the machine: no network listener, no telemetry, no account.
- Credentials are never written to a log, an error message, or a JDBC URL.
- Redis console commands are not recorded — a command's arguments are where its
  secrets are.

### Known limitations

- Installers are unsigned. macOS and Windows will warn on first launch; the README
  documents the exact steps.
- Windows on ARM has no installer yet; the x86-64 build runs under emulation.
- The result grid is read-only.
- A forgotten master password cannot be recovered.

[Unreleased]: https://github.com/sukhrobtokhirov/caracal/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/sukhrobtokhirov/caracal/releases/tag/v0.1.0
