package dev.caracal.engine.postgres

import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.sql.StatementSplitter
import dev.caracal.engine.api.SourcePosition
import dev.caracal.engine.api.StatementRequest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

/**
 * The error position, carried across the SPI boundary.
 *
 * `ErrorPositionTest` pins down the mapping itself, on `Statement.documentIndex`.
 * This pins down that the crossing *uses* it — that a `StatementRequest`'s
 * `sourceOffset` and text reconstruct the same statement the splitter produced, and
 * that a server position therefore still lands on the same character it did before
 * there was an SPI. The two together are what stop the underline drifting silently:
 * one of them would pass with the mapping intact and the wiring wrong.
 */
class PostgresEngineErrorsTest {

    @Test
    fun `a position in the first statement of a buffer lands on the character`() {
        val buffer = "SELECT nope FROM t;"

        val error = errorAt(buffer, statementIndex = 0, position = 8)

        // 1-based position 8 is the `n` of `nope`, which is offset 7.
        assertEquals(SourcePosition(7), error.position)
        assertEquals('n', buffer[7])
    }

    @Test
    fun `a position in the second statement is offset by where that statement starts`() {
        val buffer = "SELECT 1;\nSELECT nope FROM t;"

        val error = errorAt(buffer, statementIndex = 1, position = 8)

        assertEquals(SourcePosition(17), error.position)
        assertEquals('n', buffer[17])
    }

    @Test
    fun `a comment inside the statement is counted by the server and by us alike`() {
        // The splitter keeps a leading comment as part of the statement, which is
        // right: it is part of what the server was sent, so the server's position
        // counts through it. The underline has to count through it too.
        val buffer = "-- a note about the query\nSELECT nope;"
        val statement = StatementSplitter.split(buffer).statements.single()
        val target = statement.text.indexOf("nope")

        val error = errorAt(buffer, statementIndex = 0, position = target + 1)

        assertEquals('n', buffer[error.position!!.offset])
        assertEquals(buffer.indexOf("nope"), error.position!!.offset)
    }

    @Test
    fun `a character outside the BMP before the error does not shift it by one`() {
        // The server counts code points; Kotlin counts UTF-16 units, and this emoji
        // is two of the latter and one of the former. Getting this wrong is the bug
        // that gets reported as "the underline is in the wrong place sometimes".
        val buffer = "SELECT '🐈', nope FROM t;"
        val target = buffer.indexOf("nope")

        // 1-based code point count up to `nope`, plus one.
        val position = buffer.codePointCount(0, target) + 1
        val error = errorAt(buffer, statementIndex = 0, position = position)

        assertEquals(SourcePosition(target), error.position)
        assertEquals("nope", buffer.substring(target, target + 4))
    }

    @Test
    fun `an error at end of input maps to the end of the statement`() {
        val buffer = "SELECT 1 FROM"
        val statement = StatementSplitter.split(buffer).statements.single()

        // PostgreSQL reports end of input as one past the last character.
        val error = errorAt(buffer, statementIndex = 0, position = statement.text.length + 1)

        assertEquals(SourcePosition(statement.end), error.position)
    }

    @Test
    fun `a position past the statement underlines nothing rather than something innocent`() {
        val buffer = "SELECT 1;"

        assertNull(errorAt(buffer, statementIndex = 0, position = 500).position)
    }

    @Test
    fun `a position of zero is not a position`() {
        // 1-based, so 0 and below cannot be mapped and must not be treated as the
        // first character.
        assertNull(errorAt("SELECT 1;", statementIndex = 0, position = 0).position)
        assertNull(errorAt("SELECT 1;", statementIndex = 0, position = -3).position)
    }

    @Test
    fun `a statement the server reported no position for has none`() {
        val request = StatementRequest(sql = "SELECT 1", sourceOffset = 0)

        val error = PostgresEngineErrors.of(DbError.QueryFailed("boom"), request)

        assertNull(error.position)
    }

    @Test
    fun `detail and hint survive the crossing, because the hint is often the answer`() {
        val request = StatementRequest(sql = "INSERT INTO t VALUES (1)", sourceOffset = 0)
        val failed = DbError.QueryFailed(
            message = "duplicate key value violates unique constraint \"t_pkey\"",
            sqlState = "23505",
            detail = "Key (id)=(1) already exists.",
            hint = "Use ON CONFLICT DO NOTHING to ignore duplicates.",
        )

        val error = PostgresEngineErrors.of(failed, request)

        assertEquals("23505", error.code)
        assertEquals("Key (id)=(1) already exists.", error.detail)
        assertEquals("Use ON CONFLICT DO NOTHING to ignore duplicates.", error.hint)
    }

    @Test
    fun `a failure that is not a query failure carries its stable code and no position`() {
        val request = StatementRequest(sql = "SELECT 1", sourceOffset = 0)
        val failure = DbException(DbError.Timeout())

        val error = PostgresEngineErrors.of(failure, request)

        assertEquals(DbError.Timeout().code, error.code)
        assertNull(error.position)
        assertSame(failure, error.cause)
    }

    @Test
    fun `the throwable is kept so a stack trace is still available to a log`() {
        val request = StatementRequest(sql = "SELECT 1", sourceOffset = 0)
        val failure = DbException(DbError.QueryFailed("boom", position = 1))

        assertSame(failure, PostgresEngineErrors.of(failure, request).cause)
    }

    /**
     * Builds the request the way the editor does — by splitting the buffer and taking
     * one statement with its own offset — so that the offsets under test are the ones
     * the product actually produces rather than ones invented for the test.
     */
    private fun errorAt(buffer: String, statementIndex: Int, position: Int) =
        StatementSplitter.split(buffer).statements[statementIndex].let { statement ->
            PostgresEngineErrors.of(
                DbError.QueryFailed("boom", sqlState = "42703", position = position),
                StatementRequest(sql = statement.text, sourceOffset = statement.start),
            )
        }
}
