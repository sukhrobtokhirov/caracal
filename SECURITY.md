# Security policy

## Reporting a vulnerability

**Do not open a public issue.**

Report privately through GitHub's advisory form — *Security → Report a
vulnerability* on the repository — or by email to suxrobtoxirov001@gmail.com.

Please include the affected version (`--version`, or the About panel in Settings),
your platform, what an attacker would gain, and the smallest reproduction you have.
Redact hostnames, usernames, and query text; a report does not need your real
database to be understood.

Expect an acknowledgement within a week. This is a single-maintainer project, so a
fix may take longer than that; you will be told which release it is going into, and
credited in the advisory unless you would rather not be.

Please give a reasonable window before publishing. There is no bug bounty.

## Supported versions

Only the latest release is supported. There is no backporting to earlier ones while
the version number begins with a zero.

| Version | Supported |
|---|---|
| 0.1.x | ✅ |
| earlier | ❌ |

## What Caracal defends against

Caracal is a local desktop application. It has no server, no network listener, no
telemetry, and no account. Everything it stores is a file on your machine.

**In scope**

- Recovering a saved database password from the configuration database without the
  master password. Credentials are sealed with AES-256-GCM under a key derived by
  Argon2id (64 MiB, 3 passes); only the salt, the cost parameters, and an encrypted
  verifier are stored.
- A credential, a Redis command argument, or a query parameter reaching a log file,
  an error message, an exported file, or the query history.
- A write reaching a server through a connection marked read-only, or a production
  connection accepting a destructive statement without its confirmation.
- A ciphertext moved between records decrypting under the wrong server — the
  connection's identity is authenticated alongside its secret specifically so that
  it cannot.
- Reading or writing outside the application's own data directory, or creating that
  directory with permissions other than owner-only.
- A malformed server response causing unbounded memory use rather than a bounded,
  reported failure.

**Out of scope**

- An attacker who already controls the running process or the user account:
  the derived key is in this process's memory while the vault is unlocked, and
  nothing in a local application can defend against a debugger attached to it.
- A forgotten master password. It is not recoverable, by design, and that is not a
  vulnerability.
- The database user's own grants. A read-only role remains the right way to browse
  production; Caracal's read-only mode is a guard, not a replacement for it.
- Gatekeeper and SmartScreen warnings on unsigned installers. These are documented
  in the README, not a defect to be worked around.
- Vulnerabilities in PostgreSQL, Redis, or a bundled dependency, unless Caracal's
  use of it is what makes them reachable. Report those upstream; tell us so the
  dependency can be moved.
