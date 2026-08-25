package dev.caracal.engine.sqlite

import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.conformance.ConnectionFixture
import dev.caracal.engine.conformance.EngineConformanceTest

/**
 * SQLite against section 10's list, and nothing of its own.
 *
 * Four lines, like PostgreSQL's, which is the claim the shared suite was built to
 * make: the bar an engine has to clear is written once, and a third engine clears it
 * by naming itself and a database.
 *
 * The one difference from `PostgresConformanceTest` is the annotation that is *not*
 * here. That class is gated on `CARACAL_INTEGRATION=1` because it needs a container;
 * this one needs a temporary file, so it runs on every `./gradlew check`. That makes
 * it the run that keeps the suite itself honest — a case broken by a refactor of
 * `:engine-api` fails on the pull request rather than on whichever night somebody
 * remembered to start Docker.
 */
class SqliteConformanceTest : EngineConformanceTest() {

    override fun engine(): DatabaseEngine = SqliteEngine()

    override fun connectFixture(): ConnectionFixture = SqliteConformanceFixture()
}
