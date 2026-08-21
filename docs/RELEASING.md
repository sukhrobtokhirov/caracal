# Releasing Caracal

The sequence below is the whole of it. Follow it in order; the steps that cannot be
automated are the ones that have historically gone wrong.

A release is built from a tag by
[`.github/workflows/release.yml`](../.github/workflows/release.yml) and from
nothing else. Nothing is ever published from a local `dist` directory.

## 1. Freeze the candidate

- [ ] `main` is green on all three CI runners.
- [ ] `version=` in [`gradle.properties`](../gradle.properties) is the version you
      are about to tag. Everything else — the installer names, the macOS build
      version, `--version`, Settings → About, and the workflow's own tag check —
      reads that one line.
- [ ] [`CHANGELOG.md`](../CHANGELOG.md) has a section for it, and the section
      matches what actually changed.
- [ ] `docs/release-notes/<version>.md` exists. The workflow attaches it verbatim;
      without it, the release gets commit-generated notes instead.

## 2. Rehearse

- [ ] Run the release workflow manually (**Actions → Release → Run workflow**). On
      anything other than a tag it builds and smoke-tests all five installers and
      creates no release. This is where a retired runner label, a missing WiX, or a
      jlink module that stopped being enough shows up — not on tag day.
- [ ] Download each rehearsal artifact and check its size is in the expected range.
      An installer far under 90 MB usually means the bundled runtime is missing.

## 3. Record the demo

- [ ] Record the README's GIF if it does not exist or no longer matches the
      product: roughly five seconds covering the connection list, a Redis key
      browsed, a SQL statement run, and the result grid.
- [ ] The application window only. No browser chrome, no terminal, no IDE.
- [ ] Synthetic connection names, hostnames, databases, users, and data. Review
      **every frame** for query text, real hostnames, notifications, wallpaper, and
      anything in a menu bar.
- [ ] Optimize it — the README should still open quickly on a phone.
- [ ] Commit it under `docs/media/` and replace the placeholder comment at the top
      of the README with the image.

## 4. Security and privacy sweep

- [ ] `git log -p` and the working tree contain no credential, token, real
      hostname, or private screenshot. Search the history, not just the tip.
- [ ] Dependency versions in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)
      are current enough to carry no known advisory you are unwilling to ship.
      Triage findings; do not take a breaking upgrade on release day.
- [ ] [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md) matches what the build
      resolves today. Re-read the licenses of anything whose version changed.
- [ ] The configuration database is still created owner-only, and no log line
      carries a credential, a Redis argument, or query parameters.

## 5. Tag

```sh
git tag -a v0.1.0 -m "Caracal 0.1.0"
git push origin v0.1.0
```

The workflow then verifies the tag against `gradle.properties`, runs the full test
gate against the tagged commit, builds five installers on five runners, launches
each packaged application to prove it starts, checksums everything, and creates a
**draft** release. It never publishes.

## 6. Verify the artifacts that were uploaded

Not local builds. Download from the draft release itself.

```sh
gh release download v0.1.0 --dir /tmp/caracal-v0.1.0
cd /tmp/caracal-v0.1.0 && sha256sum --check --ignore-missing checksums.txt
```

Then, on each platform you have access to:

**macOS** (Apple Silicon and Intel)

- [ ] The `.dmg` mounts, the app drags to Applications, and it launches from
      Finder and the Dock.
- [ ] Gatekeeper behaves exactly as the README describes — including that
      **Open Anyway** appears where the README says it does.
- [ ] `Caracal.app/Contents/MacOS/Caracal --version` prints the tagged version.
- [ ] Window size and position survive a restart; `Cmd+Q` shuts down cleanly.
- [ ] The data directory is created owner-only under
      `~/Library/Application Support/caracal`.

**Linux** (x86-64 and ARM64)

- [ ] `sudo apt install ./caracal_<version>_linux_<arch>.deb` succeeds on a clean
      Ubuntu 24.04, and Caracal appears in the applications menu.
- [ ] It runs under both an X11 and a Wayland session.
- [ ] The data directory follows XDG conventions.
- [ ] Closing the window from a terminal launch shuts the pools down cleanly.

**Windows** (x86-64)

- [ ] The `.msi` installs, and Caracal launches from the Start menu and Explorer
      with no console window flashing.
- [ ] SmartScreen behaves as the README describes.
- [ ] Data lands under `%AppData%\caracal`, and a non-ASCII username does not
      break the path.
- [ ] Installing over a previous version upgrades it rather than duplicating it.

On every platform:

- [ ] First run asks for a master password.
- [ ] A PostgreSQL and a Redis connection can be added, tested, and opened.
- [ ] A query runs and a key browses.
- [ ] Restart, unlock, and both connections are still there.

Whatever you could not test, say so in the release notes. A tested subset stated
honestly is worth more than an untested claim of five platforms.

## 7. Publish

- [ ] Read the draft's notes once more for anything private.
- [ ] Publish the release.
- [ ] On a machine with neither a JDK nor this repository, follow the README from
      the download link to a successful query. Time it. If it takes more than two
      minutes with credentials in hand, the README is the bug.

## If something is wrong after the tag

Do not move or delete a tag anyone could have fetched. Leave the draft unpublished
if the problem is caught before publishing; otherwise fix forward and release the
next patch version. A tag that changed what it points at is worse than a version
number that skipped.
