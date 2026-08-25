package dev.caracal.engine.sqlite

import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.ConnectionId
import dev.caracal.engine.api.ConnectionTarget
import dev.caracal.engine.api.SecretBundle
import dev.caracal.engine.conformance.ConnectionFixture
import dev.caracal.engine.conformance.FailedConnection
import dev.caracal.engine.conformance.RoundTrip
import dev.caracal.engine.conformance.SqlFixture
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * SQLite's half of the conformance suite: a file, and the dialect.
 *
 * Shorter than PostgreSQL's by everything that is a server. There is no container, no
 * host, no user, no password and nothing to wait for — which is why this suite runs in
 * an ordinary `./gradlew check` rather than behind `CARACAL_INTEGRATION=1`, and why
 * SQLite is the engine that keeps section 10's list honest on every pull request
 * instead of only on the nights a container is available.
 *
 * There is no assertion in this file and there should never be one. The cases belong
 * to `EngineConformanceTest`; a fixture that started making claims of its own would be
 * the beginning of SQLite having a different definition of done from every other
 * engine.
 */
class SqliteConformanceFixture : ConnectionFixture() {

    override val descriptor = ConnectionDescriptor(
        id = ConnectionId("conformance"),
        engineId = SqliteEngine.ID,
        displayName = "Conformance",
        target = ConnectionTarget.File(database, createIfMissing = false),
    )

    /**
     * SQLite authenticates nobody, and this is the value that says so.
     *
     * [SecretBundle.None] is a case in the sum type rather than a null, which is the
     * Phase 3 generalization this engine is here to test. A null would have meant
     * "we could not find one", and every caller would have had to guess which.
     */
    override val secrets: SecretBundle = SecretBundle.None

    /**
     * Nothing to grep for, and the suite is told so rather than left to infer it.
     *
     * An empty list here passes both redaction cases against any engine, correct or
     * not, which is why the suite refuses to run them on it: this engine's form
     * declares no secret field, so the two cases skip and say which declaration sent
     * them away. That is a visible line in the report rather than two green ticks for
     * assertions that grepped for nothing.
     */
    override val secretLiterals = emptyList<String>()

    /**
     * SQLite has no major version but the library does, and the library is what
     * answers a query here.
     *
     * Hard-coded rather than read from the driver, because a fixture that asked the
     * same object under test what to expect would assert nothing. `3` is a claim about
     * a file format that has not changed since 2004 and is documented not to.
     */
    override val serverMajor = 3

    /**
     * A path that names nothing, inside a directory that does not exist either.
     *
     * Both halves on purpose: a missing file in a real directory and a missing
     * directory are the same `SQLITE_CANTOPEN` to SQLite, and this is the version that
     * cannot be accidentally created by another test running beside it.
     */
    override val unreachable = FailedConnection(
        descriptor = descriptor.copy(
            target = ConnectionTarget.File(
                directory.resolve("no-such-directory").resolve("absent.db"),
                createIfMissing = false,
            ),
        ),
        secrets = SecretBundle.None,
        evidence = listOf("could not be opened"),
    )

    /**
     * No credential this database will refuse, because it refuses none.
     *
     * The suite only requires one from an engine whose form declares a secret field,
     * and asserts that requirement rather than trusting it — so leaving this null is
     * checked against the form rather than merely accepted.
     */
    override val refusedCredentials = null

    override val sql = SqlFixture(
        // The only namespace SQLite has, and it is both of these. `main` is the file
        // itself: there is no schema above a table to put scratch work in, and no
        // system catalogue in a separate namespace to read — `sqlite_schema` lives in
        // `main` beside everything else.
        scratchSchema = "main",
        systemSchema = "main",
        // Null, and this is the declaration the whole engine turns on. SQLite has no
        // exact decimal: `DECIMAL(30,10)` is a declared type over an IEEE double, and
        // the digits past the fifteenth are gone before anything can read them back.
        // The engine declares `exactNumerics = false` and the suite checks that this
        // is null in the same breath, so neither can be changed alone.
        exactDecimal = null,
        // A bare integer literal. SQLite stores it as a 64-bit integer and keeps every
        // digit of it, which is the half of numeric exactness this engine does have.
        largeInteger = RoundTrip("9223372036854775807", "9223372036854775807"),
        // A billion iterations of a recursive CTE, which is minutes of work and stops
        // the instant `sqlite3_interrupt` sets its flag. Bounded rather than infinite
        // so that a cancellation which silently fails ends as a timeout rather than as
        // a test run that never finishes.
        slowStatement = "WITH RECURSIVE counter(x) AS (" +
            "SELECT 1 UNION ALL SELECT x + 1 FROM counter WHERE x < 1000000000" +
            ") SELECT count(*) FROM counter",
        // A column that does not exist. SQLite names the token it could not resolve,
        // which is the most a SQLite error ever carries — there is no detail, no hint
        // and no position.
        failingStatement = "SELECT nope FROM sqlite_schema",
        writeProbe = "CREATE TABLE $PROBE_TABLE (id int)",
        writeProbeCleanup = "DROP TABLE IF EXISTS $PROBE_TABLE",
        // Null, because SQLite has no second channel to say anything on. Asserted
        // against `surfacesNotices = false` rather than assumed.
        notice = null,
    )

    /**
     * Statements the classifier must never call read-only.
     *
     * The last three are SQLite's own, and the first of them is the reason
     * [SqliteIntent] exists rather than being the shared classifier used directly:
     * `REPLACE INTO` is `INSERT OR REPLACE`, it deletes the rows it conflicts with,
     * and no other dialect in this repository has it as a leading keyword.
     */
    override val writes = listOf(
        "INSERT INTO t VALUES (1)",
        "UPDATE t SET a = 1",
        "DELETE FROM t",
        "CREATE TABLE t (id int)",
        "ALTER TABLE t ADD COLUMN b int",
        "DROP TABLE t",
        "VACUUM",
        "WITH gone AS (DELETE FROM t RETURNING *) SELECT * FROM gone",
        "REPLACE INTO t VALUES (1)",
        "  replace into t values (1)",
        "INSERT OR REPLACE INTO t VALUES (1)",
        // Not classified as a write — no keyword table can tell `PRAGMA page_size`
        // from `PRAGMA journal_mode = WAL` without a list that is wrong the next time
        // SQLite gains one — and here because the rule under test is that it must not
        // come back read-only.
        "PRAGMA journal_mode = WAL",
    )

    override val reads = listOf(
        "SELECT 1",
        "select * from sqlite_schema",
        "VALUES (1)",
        "EXPLAIN SELECT 1",
        "WITH one AS (SELECT 1) SELECT * FROM one",
        // The function whose name is the leading keyword above. A classifier that
        // matched `REPLACE` anywhere would call this a write, which is the false
        // positive that teaches people to click through confirmations.
        "SELECT replace(name, 'a', 'b') FROM sqlite_schema",
    )

    companion object {
        private const val PROBE_TABLE = "conformance_read_only_probe"

        /**
         * One directory for every conformance run in this JVM, deleted on the way out.
         *
         * `deleteOnExit` registers in reverse order, so the file has to be registered
         * after the directory for the directory to be empty when its turn comes.
         */
        private val directory: Path = Files.createTempDirectory("caracal-sqlite-conformance")
            .also { it.toFile().deleteOnExit() }

        /**
         * The database every case opens, seeded once.
         *
         * Seeded through plain JDBC rather than through this engine, because a fixture
         * that built its own world with the object under test would pass for an engine
         * that could neither create a table nor read one back — it would simply do both
         * wrong consistently.
         *
         * What is in it is the minimum the suite needs and nothing that looks like an
         * assertion: a table so the catalogue has something to list and columns to
         * describe, and a view so the two kinds are not the same query with a different
         * word in it.
         */
        private val database: Path = directory.resolve("conformance.db").also { path ->
            path.toFile().deleteOnExit()
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "CREATE TABLE widgets (id INTEGER PRIMARY KEY, name TEXT NOT NULL, price REAL)",
                    )
                    statement.executeUpdate("INSERT INTO widgets (id, name, price) VALUES (1, 'anvil', 9.5)")
                    statement.executeUpdate("CREATE VIEW cheap AS SELECT * FROM widgets WHERE price < 10")
                }
            }
        }
    }
}
