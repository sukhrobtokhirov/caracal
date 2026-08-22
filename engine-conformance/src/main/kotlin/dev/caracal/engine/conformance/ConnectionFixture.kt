package dev.caracal.engine.conformance

import dev.caracal.engine.api.ConnectionDescriptor
import dev.caracal.engine.api.SecretBundle
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * The half of a conformance run that belongs to the engine: a server to talk to, and
 * the handful of sentences only this engine's dialect can spell.
 *
 * [EngineConformanceTest] has two abstract members and this is the interesting one.
 * Everything a case needs that is not derivable from [dev.caracal.engine.api.EngineCapabilities]
 * arrives through here, and the rule for deciding whether something belongs is short:
 * if the suite could write it once for every engine, it does, and if it cannot, the
 * fixture says it. `SELECT` is not the same string everywhere, a decimal literal is
 * spelled four ways, and no amount of shared code makes that untrue.
 *
 * What must **not** be here is a switch on which engine is running. A fixture is
 * written by the engine's own module and knows exactly one engine; a `when` in the
 * suite would know all of them, which is the arrangement this whole exercise exists
 * to prevent.
 *
 * ### Lifecycle
 *
 * JUnit builds a new test instance per test method, so [EngineConformanceTest.connectFixture]
 * is called once per case and [close] runs when that case ends. Anything expensive —
 * a container above all — belongs in a `companion object` that outlives the run.
 * What belongs *here* is what one case made and one case should clean up.
 */
abstract class ConnectionFixture : AutoCloseable {

    /** A descriptor that reaches the fixture server. */
    abstract val descriptor: ConnectionDescriptor

    /** The credentials [descriptor] needs. [SecretBundle.None] for a server with none. */
    abstract val secrets: SecretBundle

    /**
     * Every literal that must never surface, in any error message or any log line.
     *
     * The password, and anything else the fixture knows is a secret — a token, a
     * passphrase, the credential half of a connection string. This is what the two
     * redaction cases grep for, and it is the single highest-value line in the whole
     * fixture: it is what mechanically holds an engine nobody has written yet to a
     * security property, including the contributor driver that builds a JDBC URL with
     * the password in it.
     *
     * It must be non-empty for any engine whose connection form declares a secret
     * field. The suite checks that rather than trusting it, because an empty list
     * here is a redaction test that greps for nothing and passes.
     */
    abstract val secretLiterals: List<String>

    /**
     * The major version the fixture server reports, or null for a server that will
     * not say — a Redis whose ACL denies `INFO` is still a working connection.
     *
     * Stated so the CI matrix proves something. `postgres:13` and `postgres:17` differ
     * in exactly one observable here, and an engine that hard-codes its version parse
     * against the image it was written on is caught by the nightly run rather than by
     * a user on an older server.
     */
    abstract val serverMajor: Int?

    /** A connection that cannot be made, and the words its failure has to use. */
    abstract val unreachable: FailedConnection

    /**
     * Credentials this server will refuse, or null for a server that authenticates
     * nobody.
     *
     * Required whenever the engine's form declares a secret field: a refused password
     * is where a password most often ends up in a message, and an engine that has one
     * and does not exercise it has skipped the case that matters.
     */
    open val refusedCredentials: FailedConnection? = null

    /**
     * The dialect-shaped half, non-null exactly when the engine's family is
     * [dev.caracal.engine.api.EngineFamily.SQL].
     *
     * "Exactly when" is asserted rather than assumed. A SQL engine whose fixture left
     * this null would silently skip six cases, and a silent skip is the failure mode
     * this suite is built to not have.
     */
    open val sql: SqlFixture? = null

    /**
     * Statements or commands this engine's classifier must never call `READ_ONLY`.
     *
     * Write one per arm the engine can classify — a row write, a schema change, a
     * destructive one — plus whatever this engine gets wrong in a way somebody has
     * already had to fix. The list is a place for regressions to live.
     */
    abstract val writes: List<String>

    /** Statements or commands the classifier must call `READ_ONLY`. */
    abstract val reads: List<String>

    /**
     * Waits until the statement the cancellation case started is actually on the
     * server.
     *
     * The default is a fixed pause, which is honest about what it is: cancelling
     * something that has not arrived yet is a different test. An engine that can
     * *ask* the server what it is running — PostgreSQL can, through
     * `pg_stat_activity` — should override this and remove the guess.
     */
    open suspend fun awaitStatementRunning() {
        delay(500.milliseconds)
    }

    override fun close() = Unit
}

/**
 * A connection that will fail, and what its failure has to say.
 *
 * [evidence] is where "names the actual failure" stops being a slogan. The messages
 * this product shows are deliberately generic about *where* — `DbError`'s contract
 * forbids a host, a port or a URL in them — so the thing worth asserting is not the
 * address but the diagnosis: a server that is not there and a password that is wrong
 * must not arrive as one sentence. The suite checks these fragments and then checks
 * that the two failures do not read the same, which is the half a fixture cannot
 * accidentally satisfy.
 */
data class FailedConnection(
    val descriptor: ConnectionDescriptor,
    val secrets: SecretBundle,
    /** Fragments the failure must contain, compared without regard to case. */
    val evidence: List<String>,
)

/**
 * What a SQL engine has to spell for itself.
 *
 * Every field here is one the suite tried to write once and could not. The literals
 * are the clearest case: `9223372036854775807::int8` is PostgreSQL, `CAST(... AS
 * SIGNED)` is MySQL, and the assertion — that the value arrives with every digit —
 * is the same for both.
 */
data class SqlFixture(
    /**
     * A schema the suite may create and drop a table in, or empty for an engine with
     * no schemas.
     */
    val scratchSchema: String,
    /** A schema whose contents the catalog case can list. `pg_catalog`, and its kin. */
    val systemSchema: String,
    /** An expression producing an exact decimal, and every digit it must come back with. */
    val exactDecimal: RoundTrip,
    /** An expression producing an integer too large for a `Long` to be safe with. */
    val largeInteger: RoundTrip,
    /** A statement that will still be running when the cancellation case cancels it. */
    val slowStatement: String,
    /** A statement the server will reject, for the redaction case to read the error of. */
    val failingStatement: String,
    /** A statement that writes, sent on a read-only session and expected to be refused. */
    val writeProbe: String,
    /** Undoes [writeProbe] if an engine let it through. Run on a writable session, failures ignored. */
    val writeProbeCleanup: String,
    /**
     * A statement whose whole output is a notice, and the text it must carry.
     *
     * Required when the engine declares
     * [dev.caracal.engine.api.EngineCapabilities.surfacesNotices], and null otherwise.
     * Both directions are asserted.
     */
    val notice: NoticeCase? = null,
)

/** An expression, and what the value it produces must look like when it comes back. */
data class RoundTrip(val expression: String, val text: String) {
    val integer: BigInteger get() = BigInteger(text)
}

/** A statement whose entire result is something the server said. */
data class NoticeCase(val statement: String, val text: String)
