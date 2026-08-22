package dev.caracal.core.result

import dev.caracal.engine.api.CommandLine
import dev.caracal.engine.api.InvalidRequestException
import dev.caracal.engine.api.ScanCursor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * That a malformed request still classifies as one, now that it is raised elsewhere.
 *
 * A cursor that is not a cursor and a command line with an unclosed quote used to
 * throw `DbException(DbError.InvalidRequest(...))` from inside `:core`. The types
 * that raise them moved into `:engine-api`, which cannot see [DbError] at all, so
 * they throw [InvalidRequestException] instead and `:core` joins it back. This is
 * the test that says the join is real: the code and the sentence the UI branches on
 * and shows are the ones they always were.
 *
 * Worth its own file because the failure it guards against is silent. Nothing would
 * stop compiling if the join were dropped — `toFailure` has an `else` arm, and it
 * would quietly turn "that is not a scan cursor" into "something went wrong".
 */
class InvalidRequestTest {

    @Test
    fun `a cursor that is not a cursor is still an invalid request`() {
        val thrown = assertThrows<InvalidRequestException> { ScanCursor.of("1 OR 1") }

        assertIs<DbError.InvalidRequest>(thrown.asDbError())
        assertEquals("invalid_request", thrown.toFailure().code)
        assertEquals(thrown.message, thrown.toFailure().message)
    }

    @Test
    fun `an unclosed quote keeps the sentence it was raised with`() {
        val thrown = assertThrows<InvalidRequestException> { CommandLine.split("""SET k "open""") }

        // The message survives the module boundary rather than being replaced by the
        // generic one, which is the whole reason this is a join and not a fallback.
        assertEquals("There is an unclosed quote in that command.", thrown.toFailure().message)
    }
}
