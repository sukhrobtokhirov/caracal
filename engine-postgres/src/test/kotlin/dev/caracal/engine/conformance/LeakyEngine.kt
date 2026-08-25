package dev.caracal.engine.conformance

import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.DriverProvider
import dev.caracal.engine.api.EngineError
import dev.caracal.engine.api.QueryFacet
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.StatementExecution
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.StatementRequest
import dev.caracal.engine.postgres.PostgresEngine
import dev.caracal.engine.api.ConnectionDescriptor
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.map

/**
 * A working engine with two defects, so the suite can be shown to catch them.
 *
 * A conformance suite is a claim about code that does not exist yet, and the only way
 * to check such a claim is to write the code it is supposed to reject. The two defects
 * here are the ones section 10 names, and they were chosen because they are what a
 * plausible contributor driver actually gets wrong rather than what is easy to detect:
 *
 * 1. **The password reaches an error message.** Not invented — building a JDBC URL
 *    with the credentials in it and handing the driver's own exception text to the UI
 *    is the ordinary way to write a driver, and it is wrong.
 * 2. **The read-only flag is dropped.** Also not invented: `policy.readOnly` is an
 *    argument that has to be *used*, and an engine that forgets it still connects,
 *    still runs every query, and still passes every test that is not looking.
 *
 * Everything else delegates to [PostgresEngine], so the run against this engine
 * differs from the run against the real one in exactly two results. That is what makes
 * the negative test worth having: it proves the suite is sensitive to the defects and
 * not merely noisy.
 */
class LeakyEngine(private val real: DatabaseEngine = PostgresEngine()) : DatabaseEngine by real {

    override suspend fun connect(
        descriptor: ConnectionDescriptor,
        secrets: SecretBundle,
        policy: SessionPolicy,
        drivers: DriverProvider,
    ): DatabaseSession {
        // Defect two. The session is opened writable whatever core resolved, which is
        // what happens when an engine treats the policy as advice.
        val session = real.connect(descriptor, secrets, policy.copy(readOnly = false), drivers)
        return LeakySession(session, passwordOf(secrets))
    }

    private fun passwordOf(secrets: SecretBundle): String = when (secrets) {
        is SecretBundle.UserPassword -> String(secrets.password)
        is SecretBundle.Password -> String(secrets.password)
        else -> ""
    }
}

private class LeakySession(
    private val real: DatabaseSession,
    private val password: String,
) : DatabaseSession by real {

    @Suppress("UNCHECKED_CAST")
    override fun <F : Any> facet(type: KClass<F>): F? {
        val found = real.facet(type) ?: return null
        return if (found is QueryFacet) LeakyQueryFacet(found, password) as F else found
    }
}

/** Defect one: the connection URL, credentials and all, appended to every failure. */
private class LeakyQueryFacet(
    private val real: QueryFacet,
    private val password: String,
) : QueryFacet {

    override val splitterConfig get() = real.splitterConfig

    override suspend fun execute(request: StatementRequest): StatementExecution {
        val execution = real.execute(request)
        return object : StatementExecution {
            override val outcomes = execution.outcomes.map { outcome ->
                if (outcome is StatementOutcome.Failed) outcome.copy(error = leak(outcome.error)) else outcome
            }

            override suspend fun cancel() = execution.cancel()
        }
    }

    private fun leak(error: EngineError) = error.copy(
        message = "${error.message} (while connected as postgresql://caracal:$password@host/db)",
    )
}
