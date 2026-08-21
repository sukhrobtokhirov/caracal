package dev.caracal.app

import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.history.ExecutionOutcome
import dev.caracal.core.history.ExecutionRecord
import dev.caracal.core.history.HistoryScope
import dev.caracal.core.vault.VaultState
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What the history panel asks for, and what it does with the answers.
 *
 * The two properties worth defending here are the ones a screenshot cannot show.
 * Paging *accumulates*, so reading further back is reading further back rather than
 * watching one page be replaced by another; and changing a filter starts again,
 * because the cursor in hand is a place in the ordering the previous filter produced
 * and carrying it over would page into the middle of a list nobody asked for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val alpha = ConnectionId("id-1")
    private val beta = ConnectionId("id-2")

    private fun service(vararg entries: ExecutionRecord) =
        FakeConnectionService(VaultState.UNLOCKED).apply { history += entries }

    private fun TestScope.model(service: FakeConnectionService) = HistoryViewModel(service, this)

    private var next = 1L

    private fun record(
        statement: String,
        connection: ConnectionId = alpha,
        outcome: ExecutionOutcome = ExecutionOutcome.OK,
        at: Instant = Instant.parse("2026-08-21T09:00:00Z"),
    ) = ExecutionRecord(
        connectionId = connection,
        statement = statement,
        outcome = outcome,
        executedAt = at,
        id = next++,
    )

    private fun HistoryViewModel.statements() = visible.map { it.statement }

    @Test
    fun `opening reads the newest page for one connection`() = runTest {
        val service = service(record("first"), record("second", connection = beta), record("third"))
        val model = model(service)

        model.open(alpha)
        advanceUntilIdle()

        assertEquals(listOf("third", "first"), model.statements())
        assertFalse(model.hasMore)
    }

    @Test
    fun `showing older adds to what is on screen rather than replacing it`() = runTest {
        val service = service(*Array(120) { record("q$it") })
        val model = model(service)

        model.open(alpha)
        advanceUntilIdle()
        assertEquals(50, model.visible.size)
        assertTrue(model.hasMore)

        model.loadMore()
        advanceUntilIdle()

        // The second page is behind the first, not instead of it: q119 is still the
        // first row on screen and the list now runs down to q20.
        assertEquals(100, model.visible.size)
        assertEquals("q119", model.statements().first())
        assertEquals("q20", model.statements().last())
        // The seam is where the real mistake would show. A cursor one row out
        // produces a list of exactly this length with either q70 in it twice or q69
        // missing, and nothing on screen says which.
        assertEquals(listOf("q70", "q69"), model.statements().subList(49, 51))
    }

    @Test
    fun `changing a filter starts again from the newest entry`() = runTest {
        val service = service(*Array(120) { record("q$it") })
        val model = model(service)

        model.open(alpha)
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        assertEquals(100, model.visible.size)

        model.showOutcome(ExecutionOutcome.OK)
        advanceUntilIdle()

        // A page, not two. The cursor from the previous filter names a place in an
        // ordering this query does not have, and carrying it over would open the
        // panel halfway down a list nobody scrolled to.
        assertEquals(50, model.visible.size)
        assertEquals("q119", model.statements().first())
    }

    @Test
    fun `the outcome filter is asked of the service rather than applied to the page`() = runTest {
        val service = service(
            record("worked"),
            record("broke", outcome = ExecutionOutcome.ERROR),
            record("stopped", outcome = ExecutionOutcome.CANCELLED),
        )
        val model = model(service)

        model.open(alpha)
        advanceUntilIdle()
        model.showOutcome(ExecutionOutcome.ERROR)
        advanceUntilIdle()

        // Filtering in the panel would filter one page of fifty and call it the
        // answer — which on a connection with a thousand entries is a claim that
        // nothing failed, made from the fifty most recent rows.
        assertEquals(listOf("broke"), model.statements())
    }

    @Test
    fun `search narrows the loaded pages and never goes back to the service`() = runTest {
        val service = service(record("select * from invoices"), record("select now()"))
        val model = model(service)

        model.open(alpha)
        advanceUntilIdle()
        val before = service.calls.count { it == "history" }

        model.searchFor("INVOICE")
        advanceUntilIdle()

        assertEquals(listOf("select * from invoices"), model.statements())
        assertEquals(before, service.calls.count { it == "history" })
    }

    @Test
    fun `a filter change forgets the search box's leftovers`() = runTest {
        val service = service(record("select now()"))
        val model = model(service)

        model.open(alpha)
        model.searchFor("nothing matches this")
        advanceUntilIdle()
        assertTrue(model.statements().isEmpty())

        // Opening the window again is a fresh question. A search term left over from
        // the last time it was open is an empty list with no visible cause.
        model.open(alpha)
        advanceUntilIdle()

        assertEquals("", model.search)
        assertEquals(listOf("select now()"), model.statements())
    }

    @Test
    fun `clearing is what the dialog said it would be, not what the filter says now`() = runTest {
        val service = service(record("alpha"), record("beta", connection = beta))
        val model = model(service)
        model.open(alpha)
        advanceUntilIdle()

        model.askClear(HistoryScope.OneConnection(alpha))
        // The panel moves on behind the open question. What goes must still be what
        // was asked about.
        model.showConnection(null)
        advanceUntilIdle()
        model.confirmClear()
        advanceUntilIdle()

        assertEquals(listOf("beta"), service.history.map { it.statement })
        assertEquals(listOf("beta"), model.statements())
    }

    @Test
    fun `a deletion that is dismissed deletes nothing`() = runTest {
        val service = service(record("alpha"))
        val model = model(service)
        model.open(alpha)
        advanceUntilIdle()

        model.askClear(HistoryScope.Everything)
        model.cancelClear()
        advanceUntilIdle()

        assertNull(model.pendingClear)
        assertFalse(service.calls.contains("clearHistory"))
        assertEquals(listOf("alpha"), model.statements())
    }

    @Test
    fun `a failed read is reported and does not leave a half-loaded list on screen`() = runTest {
        val service = service(record("alpha"))
        service.nextFailure = IllegalStateException("the configuration database is gone")
        val model = model(service)

        model.open(alpha)
        advanceUntilIdle()

        assertIs<HistoryLoad.Failed>(model.state)
        assertTrue(model.visible.isEmpty())
    }

    @Test
    fun `locking drops every statement the panel had read`() = runTest {
        val service = service(record("select * from customers where email = 'a@b.c'"))
        val model = model(service)
        model.open(alpha)
        advanceUntilIdle()
        assertTrue(model.visible.isNotEmpty())

        model.clear()

        // The panel holds the query text in memory. A locked application still
        // holding it is a locked application still showing what locking is for.
        assertIs<HistoryLoad.Loading>(model.state)
        assertTrue(model.visible.isEmpty())
        assertNull(model.connectionId)
    }
}
