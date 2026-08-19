# M5 — Release

## Outcome

Publish v0.1.0 as trustworthy prebuilt executables for macOS, Linux, and Windows on AMD64 and ARM64. A stranger should understand the product, download the right artifact, launch it, and connect to PostgreSQL or Redis in under two minutes.

Release work is part of the product: clean builds, safe defaults, accurate documentation, checksums, licensing, and a reproducible tag-based process.

## Scope

### Included

- Final searchable project name and positioning
- Apache-2.0 license and contributor documentation
- README led by an animated product demonstration
- GoReleaser configuration
- GitHub Actions test and release workflows
- `darwin`, `linux`, and `windows` builds for `amd64` and `arm64`
- Version metadata, archives, and checksums
- Issue and pull-request templates
- v0.1.0 release notes and tag procedure
- Clean-machine installation smoke tests

### Not included

- Auto-update system
- Package-manager formulas/manifests
- macOS notarization, Windows code signing, or paid certificates unless already available
- Linux distribution repositories
- Telemetry or crash-reporting service
- Hosted application or cloud sync

Unsigned binaries can trigger operating-system warnings. Document this honestly; do not weaken application security or ask users to disable protections globally.

## Release deliverables

Expected release artifacts:

```text
<name>_<version>_darwin_amd64.tar.gz
<name>_<version>_darwin_arm64.tar.gz
<name>_<version>_linux_amd64.tar.gz
<name>_<version>_linux_arm64.tar.gz
<name>_<version>_windows_amd64.zip
<name>_<version>_windows_arm64.zip
checksums.txt
```

Each archive should contain the executable plus the license and a concise README/install note. The executable still contains the complete frontend and needs no loose runtime assets.

## Work packages

### 5.1 Freeze product identity and scope

Before publishing:

- Choose a short, pronounceable, searchable name.
- Search the name on GitHub, major package registries, general search, and relevant trademark databases before committing.
- Confirm a matching or acceptable repository name.
- Use the positioning sentence consistently: **Postgres and Redis in one free tool.**
- State that v0.1 is a local single-user tool and list supported engines/features precisely.
- Keep deferred features in a public roadmap or issue list without implying they ship in v0.1.

Do not rename Go modules, binary paths, storage directories, and screenshots independently. Decide the name once, make a checklist of every identifier, and migrate them together before users create persistent local data.

### 5.2 Add legal and community files

Add at repository root:

- `LICENSE` containing the complete Apache License 2.0 text;
- `NOTICE` if the chosen dependency/licensing review requires notices;
- `CONTRIBUTING.md` with setup, architecture summary, tests, formatting, scope boundaries, and pull-request expectations;
- `CODE_OF_CONDUCT.md`, usually the Contributor Covenant, if accepting community participation;
- `SECURITY.md` describing private vulnerability reporting and supported versions;
- `CHANGELOG.md` or a clearly documented release-note convention.

Review production dependencies and bundled frontend assets for compatible licenses and required notices. Record direct dependencies and their purpose. Do not copy license text from memory; use authoritative full texts during implementation.

`CONTRIBUTING.md` should explain the non-negotiable safety constraints:

- loopback-only binding and per-process token;
- no plaintext credential storage/logging;
- PostgreSQL precision-safe encoding;
- bounded query/value responses;
- Redis browsing never uses `KEYS`;
- read-only and production guards must be enforced server-side.

### 5.3 Write the README around first success

The README should lead with:

1. Project name and one-line positioning.
2. A short animated GIF showing connection selection, Redis key browsing, SQL execution, and the result grid in roughly five seconds.
3. Three to six core features.
4. Download and launch instructions.
5. Security model and current limitations.

Recommended structure:

```text
Name + one-line pitch
Animated demo
Why / core features
Download
Run on macOS, Linux, Windows
First connection
Security model
Build from source
Current limitations / roadmap
Contributing
License
```

Instructions must not require Go or Node.js for users downloading a release. Give platform-specific executable commands and explain the local URL/token behavior. Mention where encrypted application data is stored and how to back up or reset it without claiming that deleting it is reversible.

The GIF must use fake/local data, hostnames, connection names, and credentials. Review every frame for query text, usernames, browser history, notifications, and other private information. Optimize it so the README remains fast.

### 5.4 Embed build and version information

Expose version metadata from the binary without starting the server:

```text
<name> version 0.1.0 (commit abc1234, built 2026-08-20T00:00:00Z)
```

Add a `version` subcommand or `--version` flag. Inject version, commit, and build date through linker flags with safe `dev` defaults for local builds.

Include the version in `GET /api/health` and an **About** view, but do not expose host build paths or CI secrets. Record the local SQLite schema version separately from the application version.

Build requirements:

- `CGO_ENABLED=0` for the intended pure-Go cross-compilation path;
- trimmed paths (`-trimpath`) to avoid leaking machine paths and improve reproducibility;
- optimized production frontend assets built before Go compilation;
- deterministic dependency installation using locked Go modules and frontend lockfile;
- no network downloads at application runtime.

### 5.5 Configure GoReleaser

Create `.goreleaser.yaml` with:

- one main package under `cmd/...`;
- binary name fixed to the project name;
- target matrix `darwin/linux/windows × amd64/arm64`;
- `CGO_ENABLED=0`;
- linker flags for version, commit, and date;
- `-trimpath` and appropriate build tags if any;
- `.tar.gz` for Unix-like targets and `.zip` for Windows;
- archive contents including `LICENSE` and concise README;
- consistent artifact naming;
- SHA-256 checksum file;
- changelog generation or explicit release-note input.

Run `goreleaser release --snapshot --clean` locally or in CI before the first tag. Inspect every archive, not just the host-platform build. Confirm the embedded `index.html` exists by launching at least one artifact and by adding a build-time/test assertion around the embedded filesystem.

GoReleaser's frontend pre-hook must use the lockfile (`npm ci` or the selected package manager's frozen install) and production build. Avoid a configuration where six target builds each perform a separate mutable frontend dependency resolution.

### 5.6 Add continuous integration

For pushes and pull requests, GitHub Actions should run:

1. Checkout with pinned major action versions.
2. Set up the required Go and Node versions.
3. Restore safe dependency caches keyed by lockfiles.
4. Install frontend dependencies using the lockfile.
5. Run frontend formatting/lint/typecheck/tests.
6. Run the production frontend build.
7. Run Go formatting check, `go vet`, unit tests, and race tests where supported.
8. Run PostgreSQL and Redis integration tests using disposable service containers on Linux.
9. Build the embedded host executable.
10. Run a lightweight launch/health/UI-asset smoke test.

Keep CI credentials minimal. Tests should use disposable database passwords and synthetic data. Upload test logs/artifacts only when they have been reviewed for query/credential leakage.

Pin database major versions intentionally and include at least the oldest supported versions in the test policy. A small latest-version job can reveal upcoming compatibility issues without silently changing the main gate.

### 5.7 Add tag-based release automation

The release workflow triggers only on version tags matching `v*` or a manual, approval-protected dispatch. It should:

- check out the exact tag with enough history for version/changelog information;
- run the full test gate again;
- verify the tag version matches the intended release version;
- build production frontend assets from the lockfile;
- run GoReleaser once;
- create a GitHub Release with archives, checksums, and prepared notes;
- use the minimum `contents: write` permission only for the release job.

Never publish from an unreviewed branch head or reuse artifacts from an unrelated commit. Protect the release environment if the repository configuration supports it.

If a release fails after creating a draft, keep it as a draft until all artifacts are verified. Do not move or recreate a public tag silently; publish a corrected patch version when users could have downloaded the original.

### 5.8 Create issue and pull-request templates

Provide focused issue forms/templates:

- **Bug report:** OS/architecture, app version, engine/version, reproduction, expected/actual result, safe logs, and confirmation that secrets were removed.
- **Feature request:** user problem, proposed workflow, alternatives, and fit with current scope.
- **Security issue:** redirects users to the private process in `SECURITY.md` rather than a public issue.

The pull-request template should ask for:

- problem and approach;
- screenshots for UI changes using synthetic data;
- tests run;
- security/data-safety impact;
- documentation changes;
- confirmation that deferred scope was not introduced accidentally.

Configure issue labels sparingly: engine (`postgres`, `redis`), area (`frontend`, `backend`, `security`, `release`), and triage state are enough initially.

### 5.9 Perform platform smoke tests

Cross-compilation success does not prove runtime success. Test the actual artifacts on clean or disposable environments:

#### macOS Intel and Apple Silicon

- executable starts and opens/prints the local URL;
- Gatekeeper behavior for the unsigned archive is accurately documented;
- application data path and file permissions are correct;
- browser opener works or fails gracefully;
- PostgreSQL/Redis TLS connection works.

#### Linux AMD64 and ARM64

- executable runs on the documented minimum libc/kernel environment, noting that a pure-Go binary reduces but does not erase OS assumptions;
- headless `--no-open` works;
- application data directory follows platform conventions;
- port selection and signal shutdown work under a terminal and container-like environment.

#### Windows AMD64 and ARM64

- `.exe` launches from PowerShell and Explorer;
- browser opening and URL quoting work;
- application data uses the correct user directory;
- SQLite file locking and shutdown/restart work;
- Windows Defender/SmartScreen behavior is documented honestly;
- long paths and non-ASCII usernames do not break storage paths.

On every platform, verify the binary contains the UI, binds only to loopback, rejects a missing token, creates/unlocks encrypted storage, and connects to both engines.

### 5.10 Run security and release checks

Before the tag:

- search repository history and current files for credentials, tokens, real hostnames, and private screenshots;
- run Go vulnerability/dependency checks and frontend dependency audit, triaging findings rather than blindly applying breaking upgrades;
- inspect the binary/archive for embedded development connection strings and source paths;
- verify no source map contains secrets or unexpected local paths; decide deliberately whether production source maps ship;
- confirm CSP, Origin checks, token behavior, loopback bind, and locked-store behavior in the release build;
- verify SQLite file permissions and that logs omit secrets/query parameters;
- test malformed/oversized HTTP inputs and bounded database responses;
- generate and verify checksums after final artifacts are built.

If an SBOM can be added through the release tooling with little maintenance burden, include it, but do not delay the core release solely for enhanced supply-chain metadata.

### 5.11 Prepare v0.1.0 release notes

Release notes should contain:

- one-paragraph product summary;
- supported platforms and architectures;
- PostgreSQL features;
- Redis features;
- security model in plain language;
- important v0.1 limitations;
- install/launch link;
- checksum verification example;
- known unsigned-binary warnings;
- where to report bugs and vulnerabilities.

Do not claim broad PostgreSQL/Redis version compatibility unless it has been tested. State the tested versions and invite reports for others.

### 5.12 Tag and verify the release

Use a written, repeatable sequence:

1. Freeze the candidate commit.
2. Run all CI and local snapshot-release checks.
3. Complete platform smoke tests or document the exact tested subset.
4. Review README/GIF/release notes for private data.
5. Create the signed or annotated `v0.1.0` tag according to project policy.
6. Let the release workflow build from that tag.
7. Download artifacts from the published/draft release, not from local `dist`.
8. Verify checksums and run final first-launch tests.
9. Publish the release.
10. Open a clean browser session and follow the README exactly as a new user.

Do not call the milestone complete until the uploaded artifacts, rather than only local builds, pass verification.

## Testing

### Automated release checks

- Clean frontend dependency install from lockfile
- Go module verification
- Full backend/frontend/unit/integration suite
- Embedded-asset presence
- Six expected target artifacts with correct executable names/extensions
- `--version` output matching the tag
- Archive contents and SHA-256 checksum verification
- Launch, health endpoint, token rejection, and static UI smoke test on available runners
- No dirty generated files after a release build

### Manual acceptance scenario

1. Use a clean machine or VM with neither Go nor Node.js installed.
2. Open the GitHub Release page and identify the correct artifact without reading source code.
3. Download it and verify its checksum using the documented command.
4. Extract and launch the executable using only README instructions.
5. Complete first-run master-password setup.
6. Add, test, and open PostgreSQL and Redis connections.
7. Run a SQL query and browse a Redis key.
8. Restart, unlock, and confirm both connections remain.
9. Confirm the entire flow takes under two minutes when server credentials are ready.
10. Repeat essential launch tests for every available platform/architecture combination.

## Completion checklist

- [ ] The final name is checked for discoverability and applied consistently.
- [ ] Apache-2.0 license and contributor/security documentation are present.
- [ ] README leads with a synthetic-data GIF and a two-minute install path.
- [ ] Version, commit, and build date are visible without starting the server.
- [ ] GoReleaser produces all six target archives plus checksums.
- [ ] CI runs frontend, Go, integration, and embedded-build checks.
- [ ] Tag-based release uses the exact reviewed commit and minimal permissions.
- [ ] Issue/PR templates emphasize safe diagnostics and scope.
- [ ] Actual release archives pass platform and clean-machine smoke tests.
- [ ] Security/dependency/secret checks are complete and findings are resolved or documented.
- [ ] v0.1.0 notes state tested platforms, security model, and limitations accurately.
- [ ] A stranger can download, launch, and connect in under two minutes.

## Exit criterion

The public v0.1.0 GitHub Release contains verified prebuilt binaries for macOS, Linux, and Windows on AMD64 and ARM64, and a new user can reach a successful PostgreSQL or Redis operation using only the release documentation.
