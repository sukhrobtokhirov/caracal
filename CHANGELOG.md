# Changelog

All notable changes to Caracal are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — while the major
version is 0, a minor bump may change behaviour.

## [Unreleased]

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

[Unreleased]: https://github.com/stohirov/caracal/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/stohirov/caracal/releases/tag/v0.1.0
