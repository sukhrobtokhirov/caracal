package dev.caracal.engine.postgres

import dev.caracal.core.connections.Secret
import dev.caracal.core.postgres.PostgresConnectionConfig
import dev.caracal.core.postgres.PostgresSession
import dev.caracal.core.result.CellValue as CoreCellValue
import dev.caracal.engine.ServerImage
import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionId
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.conformance.ConnectionFixture
import dev.caracal.engine.conformance.FailedConnection
import dev.caracal.engine.conformance.NoticeCase
import dev.caracal.engine.conformance.RoundTrip
import dev.caracal.engine.conformance.SqlFixture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.testcontainers.containers.PostgreSQLContainer

/**
 * PostgreSQL's half of the conformance suite: a server, and the dialect.
 *
 * Everything here is either an address or a sentence only PostgreSQL spells this way.
 * There is no assertion in this file and there should never be one — the cases belong
 * to `EngineConformanceTest`, and a fixture that started making claims of its own
 * would be the beginning of PostgreSQL having a different definition of done from
 * every other engine.
 */
class PostgresConformanceFixture : ConnectionFixture() {

    override val descriptor = ConnectionDescriptor(
        id = ConnectionId("conformance"),
        engineId = PostgresEngine.ID,
        displayName = "Conformance",
        target = ConnectionTarget.Network(
            host = server.host,
            port = server.firstMappedPort,
            database = server.databaseName,
        ),
        engineOptions = mapOf(PostgresEngine.OPTION_USER to USER),
    )

    override val secrets: SecretBundle = SecretBundle.UserPassword(USER, PASSWORD.toCharArray())

    override val secretLiterals = listOf(PASSWORD)

    override val serverMajor = ServerImage.postgresMajor

    /**
     * A port nothing is listening on, on a host that answers immediately.
     *
     * Deliberately not a hostname that does not resolve: a DNS lookup that has to time
     * out makes this case slow on some networks and flaky on others, and what is being
     * tested is the classification of a refused connection rather than the resolver.
     */
    override val unreachable = FailedConnection(
        descriptor = descriptor.copy(
            target = ConnectionTarget.Network(host = "127.0.0.1", port = 1, database = server.databaseName),
        ),
        secrets = secrets,
        evidence = listOf("could not be reached"),
    )

    override val refusedCredentials = FailedConnection(
        descriptor = descriptor,
        secrets = SecretBundle.UserPassword(USER, "not-the-password".toCharArray()),
        evidence = listOf("Authentication failed"),
    )

    override val sql = SqlFixture(
        scratchSchema = "public",
        systemSchema = "pg_catalog",
        // Twenty digits before the point and ten after: past a Double, past a Long, and
        // past anything that quietly rounds on the way through.
        exactDecimal = RoundTrip("12345678901234567890.1234567890::numeric", "12345678901234567890.1234567890"),
        largeInteger = RoundTrip("9223372036854775807::int8", "9223372036854775807"),
        slowStatement = "SELECT pg_sleep(30)",
        // A column that does not exist, which PostgreSQL reports with a position — the
        // error carrying the most fields for the redaction case to read.
        failingStatement = "SELECT nope FROM pg_class",
        writeProbe = "CREATE TABLE $PROBE_TABLE (id int)",
        writeProbeCleanup = "DROP TABLE IF EXISTS $PROBE_TABLE",
        notice = NoticeCase(
            statement = "DO \$\$ BEGIN RAISE NOTICE '$NOTICE'; END \$\$",
            text = NOTICE,
        ),
    )

    override val writes = listOf(
        "INSERT INTO t VALUES (1)",
        "UPDATE t SET a = 1",
        "DELETE FROM t",
        "TRUNCATE t",
        "CREATE TABLE t (id int)",
        "ALTER TABLE t ADD COLUMN b int",
        "DROP TABLE t",
        // Both of these begin with a word in the read set and modify anyway. The
        // locking clause is the one that was actually got wrong once: `FOR SHARE`
        // contains no modifying keyword, classified as a read, and failed at the
        // server with 25006 — the raw error the classifier exists to pre-empt.
        "WITH gone AS (DELETE FROM t RETURNING *) SELECT * FROM gone",
        "SELECT * FROM t FOR SHARE",
    )

    override val reads = listOf(
        "SELECT 1",
        "select * from pg_class",
        "TABLE pg_class",
        "VALUES (1)",
        "SHOW server_version",
        "EXPLAIN SELECT 1",
        "WITH one AS (SELECT 1) SELECT * FROM one",
    )

    /**
     * Waits until the sleeping statement is actually on the server.
     *
     * PostgreSQL can be asked, so it is asked rather than slept at: cancelling a
     * statement that has not arrived yet is a different test, and the one thing worse
     * than a slow test here is one that passes for the wrong reason on a fast machine.
     */
    override suspend fun awaitStatementRunning() = withTimeout(BACKEND_TIMEOUT) {
        PostgresSession(config()).use { observer ->
            while (sleepingBackends(observer) == 0L) delay(POLL)
        }
    }

    /**
     * How many backends are *running* the sleep, which is not the same question as how
     * many have run one.
     *
     * `pg_stat_activity.query` keeps the last statement a backend executed long after
     * it has finished, so a backend that ran the cancellation case a moment ago and is
     * now idle still matches the text. Without `state = 'active'` this counts it, the
     * wait ends before the new statement reaches the server, and the case cancels
     * something that was never running — a pass for the wrong reason, on a suite whose
     * point is not to have any. `:core:test` runs this case twice against one
     * container, once for the real engine and once for the broken one, which is exactly
     * where the stale row comes from.
     */
    private suspend fun sleepingBackends(observer: PostgresSession): Long {
        val result = observer.adapter.execute(
            """
            SELECT count(*) AS running FROM pg_stat_activity
            WHERE query LIKE 'SELECT pg_sleep%' AND state = 'active' AND pid <> pg_backend_pid()
            """.trimIndent(),
        )
        return (result.rows.single().single() as CoreCellValue.Integer).value
    }

    private fun config() = PostgresConnectionConfig(
        host = server.host,
        port = server.firstMappedPort,
        database = server.databaseName,
        user = USER,
        password = Secret(PASSWORD),
    )

    companion object {
        const val USER = "caracal"

        /**
         * A password nothing else in the repository uses.
         *
         * Testcontainers' default is `test`, which is a word that appears in every
         * other line of a test run's log output. Grepping for it would report a leak
         * on every engine and mean nothing, so the redaction cases get a literal that
         * can only have come from here.
         */
        const val PASSWORD = "quartz-lantern-embargo-5518"

        private const val NOTICE = "the whole output of this statement"

        private const val PROBE_TABLE = "conformance_read_only_probe"

        private val BACKEND_TIMEOUT = 10.seconds

        private val POLL = 50.milliseconds

        /**
         * One server for every conformance run in this JVM.
         *
         * Started here rather than per fixture because JUnit builds a fixture per case
         * and a container per case would be minutes of waiting for a suite that spends
         * milliseconds asserting.
         */
        val server: PostgreSQLContainer<*> = PostgreSQLContainer(ServerImage.postgres)
            .withUsername(USER)
            .withPassword(PASSWORD)
            .also { it.start() }
    }
}
