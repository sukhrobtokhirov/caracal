package dev.caracal.engine.redis

import dev.caracal.core.redis.RedisFixture
import dev.caracal.engine.ServerImage
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionId
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.conformance.ConnectionFixture
import dev.caracal.engine.conformance.EngineConformanceTest
import dev.caracal.engine.conformance.FailedConnection
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Redis against the same list, and the skips are the interesting output.
 *
 * Seven of the fifteen cases do not run here, and every one of them says why in the
 * language of a declaration: `family is KEY_VALUE` sends away the five that need a
 * statement, `readOnlyEnforcement is COMMAND_GUARD_ONLY` one more and
 * `surfacesNotices is false` the last. That list is the honest shape of the second
 * engine, and having it printed in a report is worth more than having those cases
 * quietly pass.
 *
 * The one that matters most is `read-only connection refuses a write at the server`.
 * Redis skips it because nothing at the server refuses anything — read-only here is
 * an allowlist in this process — and the connection list says so with a different
 * badge for exactly that reason. A suite that had asserted the case instead of
 * skipping it would have had to be weakened until it passed, and the weakened version
 * would then have been what PostgreSQL was held to.
 */
@EnabledIfEnvironmentVariable(named = "CARACAL_INTEGRATION", matches = "1")
class RedisConformanceTest : EngineConformanceTest() {

    override fun engine(): DatabaseEngine = RedisEngine()

    override fun connectFixture(): ConnectionFixture = RedisConformanceFixture()
}

/**
 * Redis's half: an address, a credential, and the commands the classifier is judged
 * on.
 *
 * No [dev.caracal.engine.conformance.SqlFixture], and the suite checks that too — a
 * key-value engine that supplied one would be a fixture claiming its engine can do
 * something the engine has not declared.
 */
class RedisConformanceFixture : ConnectionFixture() {

    override val descriptor = ConnectionDescriptor(
        id = ConnectionId("conformance"),
        engineId = RedisEngine.ID,
        displayName = "Conformance",
        target = ConnectionTarget.Network(
            host = RedisFixture.host,
            port = RedisFixture.port,
            database = RedisFixture.CONFORMANCE_DB.toString(),
        ),
        engineOptions = mapOf(RedisEngine.OPTION_USER to RedisFixture.CONFORMANT),
    )

    override val secrets: SecretBundle =
        SecretBundle.UserPassword(RedisFixture.CONFORMANT, RedisFixture.CONFORMANCE_PASSWORD.toCharArray())

    override val secretLiterals = listOf(RedisFixture.CONFORMANCE_PASSWORD)

    override val serverMajor = ServerImage.redisMajor

    override val unreachable = FailedConnection(
        descriptor = descriptor.copy(
            target = ConnectionTarget.Network(host = "127.0.0.1", port = 1, database = "0"),
        ),
        secrets = secrets,
        evidence = listOf("could not be reached"),
    )

    override val refusedCredentials = FailedConnection(
        descriptor = descriptor,
        secrets = SecretBundle.UserPassword(RedisFixture.CONFORMANT, "not-the-password".toCharArray()),
        evidence = listOf("rejected the username or password"),
    )

    /**
     * Commands that must never be called read-only, one per answer the guard has.
     *
     * `SET` and `HSET` are simply not on the allowlist, so they classify as `UNKNOWN`
     * — which is the allowlist working: with several hundred commands and more in
     * every module, "not on it" honestly means we do not know. `FLUSHDB` is on the
     * dangerous list and classifies as `DESTRUCTIVE`. Neither may ever be `READ_ONLY`,
     * and that is the only claim this case makes.
     */
    override val writes = listOf(
        "SET key value",
        "HSET hash field value",
        "DEL key",
        "FLUSHDB",
        "FLUSHALL",
        "CONFIG SET appendonly no",
    )

    override val reads = listOf(
        "GET key",
        "PING",
        "TYPE key",
        "TTL key",
        "SCAN 0",
        "HGETALL hash",
    )
}
