# Third-party notices

A Caracal installer carries its dependencies and a trimmed Java runtime inside it,
so everything listed here is redistributed rather than merely built against. The
licenses below were read from each component's published metadata at the version
Caracal resolves — not from memory — and are re-checked when a version changes.

Caracal itself is Apache-2.0. Nothing here restricts that; the strongest term in
the list is the classpath-excepted GPL of the bundled Java runtime, which exists
precisely so that an application shipped with it stays under its own license.

## Bundled Java runtime

`jpackage` links a minimal runtime into every installer with `jlink`. It comes from
whatever JDK performed the build — [Eclipse Temurin](https://adoptium.net) 25 on
this project's release runners — and is licensed under the **GNU General Public
License, version 2, with the Classpath Exception**
([text](https://openjdk.org/legal/gplv2+ce.html)). The exception is what allows
Caracal's own code to be distributed alongside it under Apache-2.0.

This is the single largest component of the download, and the reason an installer
is 60–100 MB rather than a few.

## Libraries

| Component | Version | License |
|---|---|---|
| [PostgreSQL JDBC Driver](https://jdbc.postgresql.org) | 42.7.13 | BSD-2-Clause |
| [Lettuce](https://github.com/redis/lettuce) | 7.7.0.RELEASE | MIT |
| [HikariCP](https://github.com/brettwooldridge/HikariCP) | 7.1.0 | Apache-2.0 |
| [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) | 3.53.2.1 | Apache-2.0 |
| [Bouncy Castle](https://www.bouncycastle.org) (`bcprov-jdk18on`) | 1.85.2 | [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html) (MIT-style) |
| [SLF4J](https://www.slf4j.org) (`slf4j-api`, `slf4j-simple`) | 2.0.17 | MIT |
| [Kotlin standard library](https://kotlinlang.org) | 2.3.21 | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 1.11.0 | Apache-2.0 |
| kotlinx-datetime, kotlinx-serialization, atomicfu | transitive | Apache-2.0 |
| [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) | 1.11.1 | Apache-2.0 |
| [Compose Material 3](https://github.com/JetBrains/compose-multiplatform) | 1.9.0 | Apache-2.0 |
| `androidx.*` runtime, lifecycle, savedstate, collection, annotation | transitive | Apache-2.0 |
| [Skiko](https://github.com/JetBrains/skiko) | 0.144.6 | Apache-2.0 |
| [Skia](https://skia.org), redistributed inside Skiko | — | BSD-3-Clause |
| [JetBrains Runtime API](https://github.com/JetBrains/JetBrainsRuntime) (`jbr-api`) | 1.9.0 | Apache-2.0 |
| [Netty](https://netty.io) (`common`, `buffer`, `handler`, `transport`, `resolver`, `codec`) | 4.2.13.Final | Apache-2.0 |
| [Reactor Core](https://projectreactor.io) | 3.6.6 | Apache-2.0 |
| [Reactive Streams](https://www.reactive-streams.org) | 1.0.4 | MIT-0 |
| [redis-authx-core](https://github.com/redis/redis-authx-core) | 0.1.1-beta2 | MIT |
| [JSpecify](https://jspecify.dev) | 1.0.0 | Apache-2.0 |
| [Checker Framework qualifiers](https://checkerframework.org) | 3.55.1 | MIT |
| [JetBrains annotations](https://github.com/JetBrains/java-annotations) | 23.0.0 | Apache-2.0 |

Netty and Reactor Core arrive under Lettuce; `checker-qual` under pgjdbc; the
`androidx.*` and Skiko artifacts under Compose. SQLite itself, compiled into
`sqlite-jdbc`, is public domain.

## Build and test only

Not redistributed, and listed for completeness: Gradle (Apache-2.0), the Kotlin and
Compose Gradle plugins (Apache-2.0), JUnit 5 (EPL-2.0), and Testcontainers (MIT).

## A correction to the plan

[`db-ide-mvp-plan.md`](db-ide-mvp-plan.md) §8 recorded Lettuce as Apache-2.0 and
listed RSyntaxTextArea (BSD-3) among the dependencies to review. Neither survived
contact with the resolved build: Lettuce 7.x is **MIT**, and the SQL editor is
Compose code in this repository rather than a Swing component, so RSyntaxTextArea
is not a dependency at all. Both are the reason this list was compiled from the
metadata of the artifacts on the runtime classpath rather than from the plan.
