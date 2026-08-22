# M5 — Release

## Outcome

Publish v0.1.0 as trustworthy prebuilt installers for macOS, Linux, and Windows. A
stranger should understand what the product is, download the right file, launch it,
and connect to PostgreSQL or Redis in under two minutes.

Release work is part of the product: a frozen name, clean builds, safe defaults,
accurate documentation, checksums, licensing, and a tag-based process that can be
repeated by someone who has forgotten how it went last time.

This milestone assumes [M4](04-product-polish.md) shipped. What is being packaged
is a desktop application, so the artifacts, the first-launch instructions, and the
demo must all show a window.

## Scope

### Included

- Final searchable name, applied to every identifier at once
- Apache-2.0 license, third-party notices, and contributor documentation
- README led by an animated product demonstration
- Version, commit, and build date reachable without opening the window
- A tag-triggered release workflow building one installer per platform
- Checksums and a draft GitHub Release
- Issue and pull-request templates
- v0.1.0 release notes and a written tag procedure
- Clean-machine installation smoke tests

### Not included

- Auto-update
- Package-manager formulas or manifests (Homebrew, winget, Flatpak)
- macOS notarization or Windows code signing — no certificates exist to sign with
- Linux distribution repositories
- Telemetry or crash reporting
- Any hosted service or cloud sync

Unsigned desktop applications trigger Gatekeeper and SmartScreen, and both are more
prominent for a windowed application than for a command-line tool. Document the
exact first-launch steps honestly. Never tell a user to disable a protection
globally.

## Release deliverables

`jpackage` produces native installers, not archives. One per platform and
architecture, named identically across all of them:

```text
caracal_<version>_macos_arm64.dmg
caracal_<version>_macos_x86_64.dmg
caracal_<version>_linux_x86_64.deb
caracal_<version>_linux_arm64.deb
caracal_<version>_windows_x86_64.msi
checksums.txt
```

Each installer contains the application, its dependencies, and a `jlink`-trimmed
Java runtime — which is why each is around 100 MB, and why nothing needs to be
installed first. The license is embedded through the packaging configuration rather
than dropped beside the executable, because there is no directory for a user to
look in.

## Work packages

### 5.1 Freeze the product identity

Choose a short, pronounceable, searchable name and apply it everywhere in one
commit. Search GitHub, Maven Central, and general search before committing to it;
a generic name means nobody finds the project, and a collision means they find
something else.

The identifiers that must move together:

- the Gradle root project and the installer's package name;
- the Kotlin package (`dev.<name>.core`, `dev.<name>.app`);
- the macOS bundle identifier and dock name;
- the Linux package name;
- **the per-user data directory and the configuration database file**;
- every environment variable the build and the application read;
- the PostgreSQL `ApplicationName` and the connection pool's name, both of which
  are visible to a database administrator in `pg_stat_activity`;
- the window title, the About panel, and every document.

Renaming the data directory is the one that can destroy something. Migrate an
existing installation on first launch rather than leaving it stranded under the old
name: move the directory, carry the write-ahead log with it, and do nothing at all
if a directory under the new name already exists.

Use the positioning sentence consistently — **A free desktop IDE for Postgres and
Redis** — and name the category in the first line. State that v0.1 is a local,
single-user tool, list what it supports precisely, and keep deferred features in
the plan or the issue tracker rather than implying they ship.

### 5.2 Add legal and community files

At the repository root:

- `LICENSE` — the complete Apache-2.0 text, fetched from an authoritative source,
  never reproduced from memory;
- `NOTICE` — the project's own copyright, pointing at the third-party list;
- `THIRD-PARTY-NOTICES.md` — every redistributed component, its version, and its
  license, read from the metadata of the artifacts the build actually resolves;
- `CONTRIBUTING.md` — setup, the module boundary, tests, scope limits, and the
  safety constraints a pull request may not weaken;
- `CODE_OF_CONDUCT.md` — the Contributor Covenant, with a real contact address;
- `SECURITY.md` — private reporting, supported versions, and an explicit statement
  of what is and is not in the threat model;
- `CHANGELOG.md`.

The bundled Java runtime is the notice that is easiest to forget and the most
important to get right: it is **GPLv2 with the Classpath Exception**, and that
exception is what allows an Apache-2.0 application to ship inside the same
installer.

`CONTRIBUTING.md` must state the constraints that are not negotiable, in the
vocabulary of the current stack: no plaintext credential is persisted or logged; no
JDBC URL is assembled with a password in it; `SQLException` messages and JDBC
metadata are scrubbed before they surface; Redis command arguments are never
logged; read-only and production guards are enforced in `:core`, never in Compose;
PostgreSQL values keep their precision; browsing never uses `KEYS`; results, scans,
and value reads stay bounded; cancellation reaches the server; `:core` never
depends on Compose.

### 5.3 Write the README around first success

Lead with the name and one-line positioning, then the demo, then what it does —
before any instruction. The structure:

```text
Name + one-line pitch
Animated demo
What it does
Download (with sizes and checksum verification)
Run it, per platform, including the unsigned first-launch steps
Your first connection
Security model, and where your data lives
Build from source
Current limitations
Contributing
License
```

Instructions must not require a JDK for anyone downloading a release. Describe the
master-password prompt as the first thing the window shows, and say plainly that it
cannot be recovered. Say where the application data directory is, how to back it up,
and that deleting it is not reversible. Be honest about the download size and about
which platform and server versions were actually tested.

The GIF shows the application window, with no browser chrome and no terminal in
frame, using synthetic connection names, hostnames, and data. Review every frame
for query text, usernames, notifications, and anything else that wandered into the
capture.

### 5.4 Embed build and version information

The application must be able to say which build it is without opening a window:

```text
Caracal 0.1.0 (commit 1a2b3c4, built 2026-08-21T22:46:03+05:00)
```

- One source of the version: `version=` in `gradle.properties`. The installer name,
  the macOS build version, the About panel, and the release workflow's tag check
  all read that.
- A Gradle task writes version, commit, and date into a properties resource; the
  application reads it from the classpath with a fallback for a build made outside
  a checkout. The commit's own timestamp is the honest choice for "built" — it
  makes two builds of the same commit describe themselves identically.
- `--version` answers and exits before anything opens the configuration database.
- The same three facts appear in Settings → About, alongside the data directory.
- A test asserts that the resource carries the version Gradle was told to build.
  Nothing else connects a build script to application code.
- macOS rejects an app version whose first number is zero. A 0.x release therefore
  carries a marketing version of `1.0.0` and the real version as the build version.
  Do not let that arithmetic reach any other surface.
- Record the SQLite schema version separately from the application version.

### 5.5 Package with `jpackage`

Compose Desktop's `nativeDistributions` block owns packaging; there is no second
tool that also wants to.

- `TargetFormat.Dmg`, `.Msi`, and `.Deb`, each built on its own platform.
- `packageName`, `vendor`, `description`, `copyright`, and `licenseFile` set from
  the build rather than defaulted.
- `modules(...)` lists the platform modules `jlink` keeps. Re-run
  `:app:suggestRuntimeModules` after adding a dependency instead of guessing —
  and remember that reflectively loaded modules such as `jdk.crypto.ec` never
  appear in its output.
- Packaging output can be redirected out of the checkout, because `codesign`
  refuses an app image carrying the extended attribute iCloud Drive attaches.

### 5.6 Keep continuous integration as the gate

`check` runs on Linux, macOS, and Windows for every push and pull request:
compilation, unit tests, Compose UI tests, the module-boundary assertion, and the
Testcontainers integration suites on the one runner with a Docker daemon. Pin the
JDK to the toolchain the build asks for. Upload test reports only on failure.

The release workflow calls that same workflow rather than keeping a second copy of
it — a release gate that has drifted from the branch gate is worse than no release
gate.

### 5.7 Add tag-based release automation

Triggered by a `v*` tag, and by a manual dispatch that builds everything and
publishes nothing. In order:

1. **Verify the tag against `gradle.properties`** and stop if they disagree.
2. Run the full test gate against the tagged commit.
3. Build one installer per platform, each on its own runner.
4. Prove the build reports the tagged version.
5. **Launch the packaged application** and check what it reports. Building
   successfully has never proved that a trimmed runtime can start.
6. Rename each installer to the common scheme. Find it by extension, never by
   version — jpackage names its own output after platform conventions.
7. Collect everything, write `checksums.txt`, and create a **draft** release with
   the prepared notes.

`contents: write` belongs to the release job alone. The draft is deliberate:
publishing is a decision taken after downloading and launching the uploaded
artifacts, not a side effect of a green build.

### 5.8 Create issue and pull-request templates

- **Bug report**: platform and architecture, `--version` output, engine and server
  version, reproduction, and a required acknowledgement that hostnames, usernames,
  and query text were removed. A stack trace from this application can carry all
  three.
- **Feature request**: the problem before the solution, and where it sits relative
  to the stated scope.
- **Security**: a contact link that routes to private reporting, with blank issues
  disabled so there is no unlabelled path.
- **Pull request**: problem and approach, tests run, screenshots with synthetic
  data, the safety checklist, and a check that deferred scope did not creep in.

### 5.9 Smoke-test the real artifacts

On a clean or disposable machine, from the uploaded files rather than a local
build. Per platform: it installs, it launches from the platform's own launcher, the
unsigned-software warning behaves exactly as documented, `--version` agrees with the
tag, the data directory is created in the right place with owner-only permissions,
window state survives a restart, and both engines connect. On Linux, check an X11
and a Wayland session. On Windows, check that no console window flashes and that a
non-ASCII username does not break the data path.

Whatever could not be tested is stated in the release notes as untested.

### 5.10 Security and release checks

Search the history and the working tree for credentials, tokens, real hostnames,
and private screenshots. Review dependency versions for known advisories and triage
rather than taking a breaking upgrade on release day. Confirm the configuration
database is still owner-only and that no log line carries a credential, a Redis
argument, or query parameters. Generate the checksums from the final artifacts and
verify them after downloading, not before uploading.

### 5.11 Prepare the release notes

One paragraph on what the product is; the platforms and architectures; what each
engine can do; the security model in plain language; the v0.1 limitations
including the unsigned installers; install and checksum instructions; and where to
report bugs and vulnerabilities. State the server versions actually tested and
invite reports for others rather than claiming broad compatibility.

### 5.12 Tag and verify

The written sequence lives in [`docs/RELEASING.md`](../RELEASING.md) and is the
authority: freeze, rehearse, record the demo, sweep for secrets, tag, verify the
uploaded artifacts, publish, then follow the README as a new user from download to
first query. Do not move or recreate a tag anyone could have fetched; release a
patch version instead.

## Testing

### Automated

- The full `check` gate on all three platforms, integration suites included.
- `--version` matches the tag, from the build and from the packaged launcher.
- A test asserting the generated build-information resource carries the project
  version.
- Five installers produced, each found under the expected extension.
- Every packaged application starts and exits cleanly.
- SHA-256 checksums generated from the final artifacts.

### Manual acceptance scenario

1. A clean machine with no JDK and no Docker.
2. Open the release page and identify the right file without reading source.
3. Verify its checksum with the documented command.
4. Install and launch using only the README.
5. Complete first-run master-password setup.
6. Add, test, and open a PostgreSQL connection and a Redis connection.
7. Run a query; browse a key.
8. Restart, unlock, and confirm both connections survived.
9. The whole flow takes under two minutes with credentials in hand.

## Deviations

Recorded against the previous version of this guide.

| The guide said | What shipped |
|---|---|
| A dedicated release tool driven by its own config file | Compose Desktop's `nativeDistributions` and `jpackage`. One tool owns packaging, as the guide itself asked — it just is not that one. |
| `.tar.gz` and `.zip` archives containing an executable | Native `.dmg`, `.deb`, and `.msi` installers. There is no loose executable to archive, and an installer is what "launch it" means on a desktop. |
| Six targets, including Windows ARM64 | Five. Windows on ARM has no dependable JDK 25 build to `jlink` from; the x86-64 installer runs under emulation, and the README says so. |
| A separate lockfile pre-hook to pin dependencies | The Gradle version catalog and wrapper are what pin this build. |
| A `version` subcommand | `--version`, which is what a desktop launcher can be given. |
| "Each archive contains the executable plus the license" | The license is set through `licenseFile` so the installer presents it; there is no archive for a user to open. |

Two further notes:

- **The name was checked, not just chosen.** Caracal collides with several
  unrelated projects on GitHub, one of them a distributed key-value store. The
  name was kept deliberately; the collision is recorded here rather than
  discovered later.
- **The plan's license review was out of date.** Plan §8 listed Lettuce as
  Apache-2.0 and RSyntaxTextArea as a dependency. Lettuce 7.x is MIT, and the SQL
  editor is Compose code in this repository. `THIRD-PARTY-NOTICES.md` is built from
  what the build resolves for exactly this reason.

## Completion checklist

- [x] The final name is applied consistently, and an installation under the old
      name is migrated rather than stranded.
- [x] Apache-2.0 license, third-party notices, and contributor, conduct, and
      security documentation are present.
- [x] The README leads with the pitch and a two-minute install path.
- [ ] The demo GIF is recorded, reviewed frame by frame, and in the README.
- [x] Version, commit, and build date are readable without opening the window, and
      a test keeps them honest.
- [x] Packaging produces five installers, each named identically across platforms,
      plus checksums.
- [x] CI runs the full gate on three platforms; the release workflow reuses it.
- [x] The tag-based release verifies the tag, builds from it, and stops at a draft.
- [x] Issue and pull-request templates ask for safe diagnostics and guard the scope.
- [ ] The uploaded artifacts pass platform and clean-machine smoke tests.
- [ ] Security, dependency, and secret checks are complete for the tagged commit.
- [x] The release notes state the tested platforms, the security model, and the
      limitations accurately.
- [ ] A stranger can download, launch, and connect in under two minutes.

The unticked items are the ones no build can sign off: they need a recording, real
machines, and a person following the README who has not read this repository.

## Exit criterion

The published v0.1.0 GitHub Release contains verified installers for macOS, Linux,
and Windows, and a new user reaches a successful PostgreSQL or Redis operation
using only the release documentation.
