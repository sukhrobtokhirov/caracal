package dev.caracal.engine.postgres

import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.conformance.ConnectionFixture
import dev.caracal.engine.conformance.EngineConformanceTest
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * PostgreSQL against section 10's list, and nothing of its own.
 *
 * The whole class is four lines because that is the claim being made: the bar an
 * engine has to clear is written once, and an engine clears it by naming itself and
 * a server. Anything PostgreSQL needs that is not in those four lines is in
 * [PostgresConformanceFixture], where it is data rather than an assertion.
 *
 * The suite it inherits overlaps `PostgresEngineIntegrationTest` in places and that
 * is not duplication worth removing. The two ask different questions: that one asks
 * whether PostgreSQL still behaves exactly as it did before the refactor, down to the
 * SQLSTATE and the character offset of an underline, and this one asks whether it
 * meets a bar the next four engines will be held to. The first will keep growing
 * PostgreSQL-shaped assertions; this one must not.
 */
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class PostgresConformanceTest : EngineConformanceTest() {

    override fun engine(): DatabaseEngine = PostgresEngine()

    override fun connectFixture(): ConnectionFixture = PostgresConformanceFixture()
}
