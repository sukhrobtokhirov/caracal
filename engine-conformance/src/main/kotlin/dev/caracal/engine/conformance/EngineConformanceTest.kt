package dev.caracal.engine.conformance

import dev.caracal.engine.api.CancelResult
import dev.caracal.engine.api.CancellationSupport
import dev.caracal.engine.api.CatalogFacet
import dev.caracal.engine.api.CatalogLimits
import dev.caracal.engine.api.CellValue
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.EngineFamily
import dev.caracal.engine.api.FormField
import dev.caracal.engine.api.ObjectKind
import dev.caracal.engine.api.QueryFacet
import dev.caracal.engine.api.QuoteStyle
import dev.caracal.engine.api.ReadOnlyEnforcement
import dev.caracal.engine.api.SessionPolicy
import dev.caracal.engine.api.SessionState
import dev.caracal.engine.api.StatementOutcome
import dev.caracal.engine.api.StatementRequest
import dev.caracal.engine.api.WriteIntent
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The tests every engine runs, whoever wrote it.
 *
 * An engine module subclasses this, supplies [engine] and [connectFixture], and gets
 * section 10's whole list. Nothing else is overridable, and that is the design: an
 * engine that could weaken a case by overriding it would eventually weaken one, and
 * the point of a shared suite is that "done" means the same thing for the fifth
 * engine as it did for the first.
 *
 * ### Skipping
 *
 * Not every case applies to every engine, and the rule for the ones that do not is
 * the load-bearing part of this file: **a case is skipped by a declared capability
 * and never by an engine's name.** A `when (engine.id)` here would mean the suite has
 * to be edited to add an engine, which is the arrangement the whole multi-engine
 * refactor exists to leave behind — and worse, it would let an engine be excused from
 * a case by a line nobody reviewing that engine would ever read.
 *
 * So a case that does not apply calls [requireCapability] and lands in the skipped
 * column, carrying the declaration that sent it there. [CapabilitySkips] then holds
 * the run to that: an unexplained skip, a skip on a case that is not
 * [CapabilityGated], a `@Disabled` case, or an engine that skipped the entire suite
 * all fail the class rather than passing it quietly. That the list below is still the
 * list — that no case has quietly stopped being discovered — is the one thing a run
 * cannot check about itself, and `SuiteIsFullyDiscoveredTest` checks it instead.
 *
 * ### The two that matter most
 *
 * `credentials never appear in error messages` and `credentials never appear in logs`
 * are the reason to build this before the engines rather than after them. They
 * enforce a security property against code nobody has written, which is the only kind
 * of enforcement that survives a contributor whose driver builds a JDBC URL with the
 * password in it and logs it at debug level. Every other case protects a feature.
 * Those two protect the user.
 */
@ExtendWith(CapabilitySkips::class)
abstract class EngineConformanceTest {

    /** The engine under test. Called once per case. */
    abstract fun engine(): DatabaseEngine

    /** A server to talk to, and the sentences this dialect spells its own way. */
    abstract fun connectFixture(): ConnectionFixture

    private val subject: DatabaseEngine by lazy { engine() }

    /**
     * Held as the delegate as well as the value, because the teardown has to be able to
     * ask whether there is anything to tear down. A case that skipped on a declared
     * capability never touched the fixture, and closing one it did not open would build
     * a fixture — a container, a connection — for the sole purpose of closing it.
     */
    private val fixtureDelegate = lazy { connectFixture() }

    private val fixture: ConnectionFixture by fixtureDelegate

    private val capabilities get() = subject.capabilities

    private val opened = mutableListOf<DatabaseSession>()

    @AfterEach
    fun releaseWhatThisCaseOpened() {
        opened.forEach { session -> runCatching { session.close() } }
        opened.clear()
        if (fixtureDelegate.isInitialized()) fixture.close()
    }

    /**
     * The fixture has the shape the engine's declarations promised.
     *
     * A [SqlFixture] is owed by a SQL engine and forbidden to every other, and a
     * [NoticeCase] is owed by an engine that declares
     * [dev.caracal.engine.api.EngineCapabilities.surfacesNotices] and forbidden to one
     * that does not. Both directions of both, in a case that runs for every engine,
     * because the alternative is asserting them inside the helper the statement cases
     * share — and a precondition that fails there is a case that did not run, dressed
     * as a case that failed for its own reason.
     */
    @Test
    fun `the fixture matches what the engine declared`() {
        val sql = fixture.sql
        if (capabilities.family == EngineFamily.SQL) {
            assertNotNull(sql, "${subject.id} declares family SQL, so its fixture owes a SqlFixture")
        } else {
            assertTrue(
                sql == null,
                "${subject.id} declares family ${capabilities.family} and its fixture supplied a SqlFixture",
            )
        }
        if (sql != null && !capabilities.surfacesNotices) {
            assertTrue(
                sql.notice == null,
                "${subject.id} declares surfacesNotices false and its fixture supplied a notice to look for",
            )
        }
    }

    @Test
    fun `connects and reports server version`() = runBlocking<Unit> {
        val session = connect()

        assertEquals(subject.id, session.engineId, "the session disagrees with its engine about who it is")
        assertEquals(capabilities, session.capabilities, "the session's capabilities are not the engine's")
        assertEquals(SessionState.Ready, session.state.value)

        val expected = fixture.serverMajor
        if (expected != null) {
            val version = session.serverVersion
            val raw = assertNotNull(version.raw, "the server did not name itself")
            assertTrue(raw.isNotBlank(), "the server's version is blank")
            assertEquals(expected, version.major, "the version parse did not survive this server: it said '$raw'")
        }
    }

    @Test
    fun `ping round trips`() = runBlocking<Unit> {
        val session = connect()

        assertTrue(session.ping() > Duration.ZERO, "a round trip that took no time did not happen")
        assertEquals(SessionState.Ready, session.state.value)
    }

    /**
     * A failure that says which failure it was.
     *
     * The messages this product shows are deliberately vague about the address —
     * `DbError` forbids a host, a port or a URL in them — so what is asserted is the
     * diagnosis rather than the detail. A server that is not there and a password that
     * is wrong are two different problems with two different fixes, and an engine that
     * collapses them into "the connection failed" has sent the user to read the wrong
     * documentation.
     */
    @Test
    fun `failed connection names the actual failure`() = runBlocking<Unit> {
        val unreachable = fixture.unreachable
        val first = failureOf(unreachable) { "connecting to an unreachable server" }
        unreachable.evidence.forEach {
            assertTrue(first.contains(it, ignoreCase = true), "an unreachable server did not say '$it': $first")
        }

        val refused = fixture.refusedCredentials
        if (takesSecrets) {
            assertNotNull(refused, "this engine's form asks for a secret, so the fixture owes a refused one")
        }
        if (refused != null) {
            val second = failureOf(refused) { "connecting with credentials the server refuses" }
            refused.evidence.forEach {
                assertTrue(second.contains(it, ignoreCase = true), "refused credentials did not say '$it': $second")
            }
            assertNotEquals(
                first,
                second,
                "a server that is not there and a credential that is wrong arrive as the same sentence",
            )
        }
    }

    /**
     * The password is nowhere in anything the user can be shown.
     *
     * Everything is read, not only the message: the detail, the hint, the internal
     * query, and the whole stack trace of whatever the driver threw, because a
     * connection URL with a password in it reaches a screen through an exception far
     * more often than through a sentence somebody wrote.
     */
    @Test
    @CapabilityGated
    fun `credentials never appear in error messages`() = runBlocking<Unit> {
        requireSecretsToGrepFor()

        val text = buildString {
            appendLine(failureOf(fixture.unreachable) { "connecting to an unreachable server" })
            fixture.refusedCredentials?.let { appendLine(failureOf(it) { "connecting with refused credentials" }) }
            val session = connect()
            appendLine(session.toString())
            appendLine(session.state.value.toString())
            appendLine(session.serverVersion.toString())
            fixture.sql?.let { appendLine(textOfFailure(session, it.failingStatement)) }
        }

        assertNoSecretsIn(text, "an error message")
    }

    /**
     * The password is nowhere in anything written to a log, at any level.
     *
     * The same operations again, with every log sink watched at once. A separate case
     * rather than a second assertion in the one above, because the leak is a different
     * leak: an error message is written by this repository, and a log line is written
     * by a driver — the half nobody here reviews.
     */
    @Test
    @CapabilityGated
    fun `credentials never appear in logs`() {
        requireSecretsToGrepFor()

        val logged = LogCapture.record {
            runBlocking {
                runCatching { subject.connect(fixture.unreachable.descriptor, fixture.unreachable.secrets, policy()) }
                fixture.refusedCredentials?.let {
                    runCatching { subject.connect(it.descriptor, it.secrets, policy()) }
                }
                val session = connect()
                session.ping()
                fixture.sql?.let { runCatching { textOfFailure(session, it.failingStatement) } }
                session.close()
            }
        }

        assertNoSecretsIn(logged, "a log line")
    }

    /**
     * A read-only session is held to it by the server.
     *
     * Only for the engines that declared they can be. The distinction is the whole
     * reason [ReadOnlyEnforcement] exists: a keyword classifier is walked straight past
     * by a function body compiled last year, and an engine with nothing but a command
     * guard must not be asserted to have something it does not have. It must be
     * *skipped*, visibly, so the missing guarantee is a line in the report.
     */
    @Test
    @CapabilityGated
    fun `read-only connection refuses a write at the server`() = runBlocking<Unit> {
        requireCapability(
            capabilities.readOnlyEnforcement != ReadOnlyEnforcement.COMMAND_GUARD_ONLY,
            "readOnlyEnforcement is COMMAND_GUARD_ONLY, so nothing at the server refuses anything",
        )
        // Gated on the family as well, because the probe this case sends is a
        // SqlFixture's. A non-SQL engine whose server does enforce read-only has a
        // guarantee worth testing and no statement here to test it with, and the honest
        // report for that is a skip naming its family rather than a failure blaming its
        // fixture for withholding a SqlFixture it was never supposed to supply.
        val sql = requireSql("a write the server can refuse is a SqlFixture's")

        try {
            val outcomes = execute(connect(readOnly = true), sql.writeProbe)
            assertNotNull(
                outcomes.filterIsInstance<StatementOutcome.Failed>().firstOrNull(),
                "a read-only session ran '${sql.writeProbe}' and the server did not refuse it. " +
                    "This connection claims ${capabilities.readOnlyEnforcement} and has none.",
            )
        } finally {
            runCatching { execute(connect(readOnly = false), sql.writeProbeCleanup) }
        }
    }

    @Test
    @CapabilityGated
    fun `exact numeric types survive round trip`() = runBlocking<Unit> {
        val sql = requireSql("exact decimals are a SQL type")

        val cell = singleCell(connect(), "SELECT ${sql.exactDecimal.expression}")
        val decimal = assertIs<CellValue.Decimal>(cell, "an exact decimal did not arrive as one: $cell")
        assertEquals(sql.exactDecimal.text, decimal.value.toPlainString(), "digits or scale were lost")
    }

    @Test
    @CapabilityGated
    fun `large integers do not lose precision`() = runBlocking<Unit> {
        val sql = requireSql("integer width is a SQL type's problem")

        val cell = singleCell(connect(), "SELECT ${sql.largeInteger.expression}")
        val integer = assertIs<CellValue.Integer>(cell, "a large integer did not arrive as one: $cell")
        assertEquals(sql.largeInteger.integer, integer.value, "an integer went through something too narrow")
    }

    /**
     * Cancel does what the engine said it does.
     *
     * The assertion is against the declaration rather than against a fixed answer,
     * because "cancel that actually cancels" is a claim the README makes and
     * [CancellationSupport.CLIENT_ABANDON] is the honest admission that an engine
     * cannot keep it. What must not happen is an engine declaring
     * [CancellationSupport.OUT_OF_BAND] and quietly abandoning the client instead.
     */
    @Test
    @CapabilityGated
    fun `cancel behaves per declared CancellationSupport`() = runBlocking<Unit> {
        val sql = requireSql("a statement to cancel is a QueryFacet's, and QueryFacet is the SQL family's")

        val execution = queryFacet(connect()).execute(StatementRequest(sql = sql.slowStatement, sourceOffset = 0))
        val collector = launch(Dispatchers.IO) { execution.outcomes.collect { } }
        fixture.awaitStatementRunning()

        val result = execution.cancel()
        withTimeout(CANCEL_TIMEOUT) { collector.join() }

        when (capabilities.cancellation) {
            CancellationSupport.OUT_OF_BAND,
            CancellationSupport.SIDE_CONNECTION,
            CancellationSupport.INTERRUPT,
            -> assertEquals(
                CancelResult.ServerAcknowledged,
                result,
                "${capabilities.cancellation} claims the server is told, and it was not",
            )

            CancellationSupport.CLIENT_ABANDON -> assertEquals(CancelResult.ClientAbandoned, result)
            CancellationSupport.NONE -> assertIs<CancelResult.Unsupported>(result)
        }
    }

    /**
     * The object browser can open a schema without reading everything inside it.
     *
     * Laziness at this seam is two observable facts rather than a timing measurement:
     * a listing is bounded and says whether it was cut, and an object's columns are a
     * second call that the first one did not make. A catalog that returned every
     * column of every table in one listing would pass neither.
     */
    @Test
    @CapabilityGated
    fun `catalog lists objects lazily`() = runBlocking<Unit> {
        val sql = requireSql("CatalogFacet is the SQL family's view of a server")
        val catalog = connect().facet(CatalogFacet::class)
            ?: fail("a SQL engine must provide a CatalogFacet; ${subject.id} does not")

        val schemas = catalog.schemas(includeSystem = true)
        assertTrue(schemas.items.isNotEmpty(), "the server listed no schemas at all")
        assertTrue(schemas.items.size <= CatalogLimits().items, "a listing came back larger than its declared bound")

        val objects = catalog.objects(sql.systemSchema, ObjectKind.TABLE)
        assertTrue(objects.items.isNotEmpty(), "${sql.systemSchema} listed no tables")
        assertTrue(objects.items.all { it.schema == sql.systemSchema }, "a listing returned another schema's objects")

        val columns = catalog.columns(sql.systemSchema, objects.items.first().name)
        assertTrue(columns.isNotEmpty(), "columns are a separate call and it returned nothing")
    }

    /**
     * What the server said on the way to a result that did not fail is not dropped.
     *
     * Some statements have no other output. A `DO` block whose whole job is to report
     * what it found returns no columns and no count, and an engine that swallows the
     * notice shows a blank grid and calls it success.
     */
    @Test
    @CapabilityGated
    fun `notices and warnings are surfaced`() = runBlocking<Unit> {
        requireCapability(capabilities.surfacesNotices, "surfacesNotices is false: this server has no second channel")
        val sql = requireSql("the statement that raises a notice is a SqlFixture's")
        val expected = assertNotNull(
            sql.notice,
            "this engine declares surfacesNotices, so the fixture owes a statement that raises one",
        )

        val outcomes = execute(connect(readOnly = false), expected.statement)
        val notices = outcomes.filterIsInstance<StatementOutcome.Notice>()
        assertTrue(notices.isNotEmpty(), "the notice was swallowed; all that arrived was $outcomes")
        assertTrue(
            notices.any { it.text.contains(expected.text) },
            "a notice arrived without what the server said: ${notices.map { it.text }}",
        )
    }

    /**
     * An identifier a person would never type survives being created and read back.
     *
     * The name catches two mistakes: an engine that does not quote at all, and an
     * engine that quotes without escaping the closer — which is a SQL injection
     * arriving through a table name. Both the name and the quoting are derived from the
     * declared [QuoteStyle], so this checks the declaration as well as the round trip.
     */
    @Test
    @CapabilityGated
    fun `identifiers are quoted correctly for round trip`() = runBlocking<Unit> {
        val sql = requireSql("identifiers are a SQL engine's")
        requireCapability(
            capabilities.identifierQuote != QuoteStyle.NONE,
            "identifierQuote is NONE: this engine has no identifiers to quote",
        )

        val qualified = qualify(sql.scratchSchema, quote(awkwardName()))
        val session = connect(readOnly = false)
        try {
            execute(session, "CREATE TABLE $qualified (id int)").assertNoneFailed("creating $qualified")
            execute(session, "INSERT INTO $qualified (id) VALUES (1)").assertNoneFailed("inserting into $qualified")

            val cell = singleCell(session, "SELECT id FROM $qualified")
            assertEquals(CellValue.Integer(BigInteger.ONE), cell, "the row did not come back")
        } finally {
            runCatching { execute(session, "DROP TABLE $qualified") }
        }
    }

    /**
     * Closing a session gives back the threads it took.
     *
     * A pool that outlives its session is invisible until the twentieth connection of
     * a long-running window, which is exactly when it is hardest to attribute. One
     * session is opened and closed before the count is taken, so that everything
     * shared — the coroutine scheduler, a driver's static machinery — already exists
     * and is not mistaken for a leak.
     */
    @Test
    fun `session closes cleanly and frees threads`() = runBlocking<Unit> {
        connect().let { warmUp ->
            warmUp.ping()
            warmUp.close()
        }
        val before = liveThreads()

        repeat(2) {
            val session = subject.connect(fixture.descriptor, fixture.secrets, policy())
            session.ping()
            session.close()
            assertEquals(SessionState.Closed, session.state.value, "a closed session does not say it is closed")
        }

        val leaked = awaitThreadsReturned(before)
        assertTrue(leaked.isEmpty(), "closing a session left ${leaked.size} thread(s) running: ${leaked.sorted()}")
    }

    /**
     * The classifier's one unbreakable rule.
     *
     * A false `WRITE` costs a confirmation dialog. A false `READ_ONLY` costs a
     * production table, so anything the classifier cannot make sense of is `UNKNOWN`
     * and never a promise. The reads are asserted too, because a classifier that
     * answered `UNKNOWN` to everything would satisfy the safety property and make
     * every `SELECT` ask permission.
     */
    @Test
    fun `intent classifier never returns READ_ONLY for a write`() {
        val classifier = subject.intentClassifier
        assertTrue(fixture.writes.isNotEmpty(), "the fixture named no writes, so this case grepped for nothing")
        assertTrue(fixture.reads.isNotEmpty(), "the fixture named no reads, so a paranoid classifier would pass")

        fixture.writes.forEach {
            assertNotEquals(WriteIntent.READ_ONLY, classifier.classify(it), "classified as read-only: $it")
        }
        UNREADABLE.forEach {
            assertNotEquals(
                WriteIntent.READ_ONLY,
                classifier.classify(it),
                "a statement the classifier cannot parse must be UNKNOWN, never READ_ONLY: '$it'",
            )
        }
        fixture.reads.forEach {
            assertEquals(WriteIntent.READ_ONLY, classifier.classify(it), "not classified as read-only: $it")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun policy(readOnly: Boolean = true) =
        SessionPolicy(readOnly = readOnly, statementTimeout = STATEMENT_TIMEOUT)

    /** A session against the fixture server, closed for the case when it ends. */
    private suspend fun connect(readOnly: Boolean = true): DatabaseSession =
        subject.connect(fixture.descriptor, fixture.secrets, policy(readOnly)).also { opened += it }

    private fun queryFacet(session: DatabaseSession): QueryFacet =
        session.facet(QueryFacet::class) ?: fail("a SQL engine must provide a QueryFacet; ${subject.id} does not")

    /**
     * Everything one statement produced, with its rows already read off the cursor.
     *
     * [StatementOutcome.Rows] carries a flow that is live only while the outcome is
     * being delivered — an engine that streams is holding a cursor, a transaction and
     * a connection open behind it — so the rows are read here, inside the emission,
     * and handed on as a flow over a list. Every case below can then look at the whole
     * result at once, which is what a test wants, without the suite quietly requiring
     * every engine to materialize, which is what a test must not ask for.
     */
    private suspend fun execute(session: DatabaseSession, sql: String): List<StatementOutcome> {
        val collected = mutableListOf<StatementOutcome>()
        queryFacet(session).execute(StatementRequest(sql = sql, sourceOffset = 0)).outcomes.collect { outcome ->
            collected += if (outcome is StatementOutcome.Rows) {
                outcome.copy(rows = outcome.rows.toList().asFlow())
            } else {
                outcome
            }
        }
        return collected
    }

    private fun List<StatementOutcome>.assertNoneFailed(what: String) {
        filterIsInstance<StatementOutcome.Failed>().firstOrNull()?.let { fail("$what failed: ${it.error.message}") }
    }

    /** The one cell of a one-row, one-column result. */
    private suspend fun singleCell(session: DatabaseSession, statement: String): CellValue {
        val outcomes = execute(session, statement)
        outcomes.assertNoneFailed(statement)
        val rows = assertNotNull(
            outcomes.filterIsInstance<StatementOutcome.Rows>().firstOrNull(),
            "'$statement' produced no rows: $outcomes",
        )
        return rows.rows.toList().single().cells.single()
    }

    /** Everything a failed statement is willing to say, including what threw. */
    private suspend fun textOfFailure(session: DatabaseSession, statement: String): String {
        val outcomes = runCatching { execute(session, statement) }
        val thrown = outcomes.exceptionOrNull()?.stackTraceToString().orEmpty()
        val failures = outcomes.getOrDefault(emptyList()).filterIsInstance<StatementOutcome.Failed>()
        // A statement that was supposed to fail and did not hands the redaction case an
        // empty string, and an empty string contains no password. That is the same
        // nothing-matched-so-it-passed this suite exists to refuse, and it is a live
        // risk on the nightly matrix, where a statement a server rejected last year can
        // become one it accepts.
        assertTrue(
            thrown.isNotEmpty() || failures.isNotEmpty(),
            "'$statement' was supposed to fail and did not, so this case read nothing to grep",
        )
        return thrown + failures.joinToString("\n") { failed ->
            with(failed.error) {
                listOfNotNull(message, code, detail, hint, internalQuery, cause?.stackTraceToString())
                    .joinToString("\n")
            }
        }
    }

    /** Everything a failed connection is willing to say, causes and stack trace included. */
    private suspend fun failureOf(connection: FailedConnection, what: () -> String): String {
        val outcome = runCatching { subject.connect(connection.descriptor, connection.secrets, policy()) }
        outcome.getOrNull()?.let {
            opened += it
            fail("${what()} succeeded; the fixture's failure case is not one")
        }
        val failure = outcome.exceptionOrNull()!!
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(MAX_CAUSES)
        return causes.joinToString("\n") { it.message.orEmpty() } + "\n" + failure.stackTraceToString()
    }

    /** Whether this engine's own form says it collects a secret. */
    private val takesSecrets: Boolean
        get() = subject.connectionForm.sections.any { section -> section.fields.any { it is FormField.Secret } }

    /**
     * Refuses to run a redaction case that would grep for nothing.
     *
     * An empty [ConnectionFixture.secretLiterals] passes both redaction tests against
     * any engine, correct or not, and it is the easiest way for this suite to become
     * decorative. An engine that genuinely has no credentials — SQLite opening a file —
     * declares no secret field on its form, and the case is skipped where a person can
     * see that it was.
     */
    private fun requireSecretsToGrepFor() {
        requireCapability(takesSecrets, "the connection form declares no secret field: there is nothing to leak")
        assertTrue(
            fixture.secretLiterals.isNotEmpty(),
            "this engine's form asks for a secret, so the fixture must name the literals to grep for",
        )
    }

    private fun assertNoSecretsIn(text: String, where: String) {
        val leaked = fixture.secretLiterals.filter { text.contains(it) }
        val evidence = text.lineSequence()
            .filter { line -> leaked.any { line.contains(it) } }
            .take(MAX_LEAK_LINES)
            .joinToString("\n")
        assertTrue(leaked.isEmpty(), "${leaked.size} credential(s) reached $where:\n$evidence")
    }

    /**
     * Skips unless this is a SQL engine, and then hands over the half of the fixture
     * only a SQL engine has.
     *
     * Every case that needs a statement goes through here, and the order is the point:
     * the family is checked *before* the fixture is asked for anything, so a non-SQL
     * engine lands in the skipped column naming its family instead of being failed for
     * not supplying a SqlFixture it was correct to omit. That the fixture's shape
     * matches the declaration in both directions is a separate case — see
     * `the fixture matches what the engine declared` — because it is a claim about the
     * fixture rather than a precondition of any one statement.
     */
    private fun requireSql(reason: String): SqlFixture {
        requireCapability(capabilities.family == EngineFamily.SQL, "family is ${capabilities.family}: $reason")
        return assertNotNull(fixture.sql, "${subject.id} declares family SQL, so its fixture owes a SqlFixture")
    }

    /**
     * A name that breaks an engine which quotes without escaping.
     *
     * The character it turns on is this engine's own closer rather than a fixed double
     * quote, which is the difference between a case that tests something and a case
     * that only tests PostgreSQL: a MySQL engine handed `weird "name` never meets a
     * backtick, so a `quote` that forgets to double them round-trips it happily and the
     * injection the case exists to catch goes on working. [QuoteStyle.NONE] has no closer and no
     * escaping to get wrong, and the case that uses this skips before reaching it.
     */
    private fun awkwardName(): String = "weird " + when (capabilities.identifierQuote) {
        QuoteStyle.DOUBLE_QUOTE -> "\""
        QuoteStyle.BACKTICK -> "`"
        QuoteStyle.BRACKET -> "]"
        QuoteStyle.NONE -> ""
    } + "name"

    private fun quote(identifier: String): String = when (capabilities.identifierQuote) {
        QuoteStyle.DOUBLE_QUOTE -> "\"" + identifier.replace("\"", "\"\"") + "\""
        QuoteStyle.BACKTICK -> "`" + identifier.replace("`", "``") + "`"
        QuoteStyle.BRACKET -> "[" + identifier.replace("]", "]]") + "]"
        QuoteStyle.NONE -> identifier
    }

    private fun qualify(schema: String, quoted: String) = if (schema.isBlank()) quoted else "$schema.$quoted"

    private fun liveThreads(): Set<String> = Thread.getAllStackTraces().keys
        .filter { it.isAlive }
        .map { it.name }
        .filterNot { name -> SHARED_THREADS.any { name.startsWith(it) } }
        .toSet()

    /**
     * Waits for the threads the sessions took to go away, and names the ones that did
     * not.
     *
     * Polled rather than measured once, because a pool's shutdown is asynchronous and
     * a driver that takes two seconds to let go has not leaked anything.
     */
    private suspend fun awaitThreadsReturned(before: Set<String>): Set<String> {
        val deadline = TimeSource.Monotonic.markNow() + THREAD_TIMEOUT
        var remaining = liveThreads() - before
        while (remaining.isNotEmpty() && deadline.hasNotPassedNow()) {
            delay(THREAD_POLL)
            remaining = liveThreads() - before
        }
        return remaining
    }

    private companion object {
        val STATEMENT_TIMEOUT = 30.seconds
        val CANCEL_TIMEOUT = 30.seconds
        val THREAD_TIMEOUT = 30.seconds
        val THREAD_POLL = 250.milliseconds
        const val MAX_CAUSES = 10
        const val MAX_LEAK_LINES = 20

        /**
         * Input no classifier can make sense of, and must therefore call `UNKNOWN`.
         *
         * Nothing here is a statement in any dialect, which is the point: the rule
         * under test is what a classifier does when it has run out of understanding,
         * and the only safe answer is the one that costs a confirmation dialog.
         */
        val UNREADABLE = listOf(
            "",
            "   ",
            "???",
            "/* unterminated",
            "'unclosed literal",
        )

        /**
         * Threads that belong to the JVM or to a shared scheduler, not to a session.
         *
         * A leak check that counted these would be flaky rather than strict: the
         * coroutine scheduler parks a worker for a minute before letting it go, the
         * collector adds and removes threads under load, and the compiler starts one
         * whenever it feels like it. None of them is a connection pool that outlived
         * its connection, which is the thing worth catching.
         */
        val SHARED_THREADS = listOf(
            "DefaultDispatcher-worker-",
            "kotlinx.coroutines",
            "ForkJoinPool.commonPool-",
            "ForkJoinPool-",
            "process reaper",
            "Common-Cleaner",
            "GC Thread#",
            "G1 ",
            "C1 CompilerThread",
            "C2 CompilerThread",
            "JFR ",
            "Notification Thread",
            "Attach Listener",
            "VM Periodic Task Thread",
        )
    }
}
