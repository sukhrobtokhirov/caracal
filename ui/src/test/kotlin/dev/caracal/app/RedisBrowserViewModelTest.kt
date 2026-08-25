package dev.caracal.app

import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.vault.VaultState
import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.MemoryEstimate
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.ScanPage
import dev.caracal.engine.api.ScanStop
import dev.caracal.engine.api.Ttl
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
 * What the key browser asks the server, and what it does with the answers.
 *
 * The assertions here are all about the two properties §3.2 and §3.4 will not compromise
 * on: that an empty page is not the end of a traversal, and that the tree is a
 * presentation of keys already in hand rather than something that fetches its own
 * children. Both are invisible on screen when they are right and disastrous when they
 * are wrong — a browser that stops on the first empty batch reports an empty keyspace
 * for a perfectly ordinary pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RedisBrowserViewModelTest {

    private val id = ConnectionId("id-1")

    private fun service() = FakeConnectionService(VaultState.UNLOCKED)

    private fun TestScope.model(service: FakeConnectionService) = RedisBrowserViewModel(service, this)

    private fun key(name: String, type: KeyType = KeyType.STRING) = KeyMetadata(
        key = KeyRef(name.toByteArray()),
        type = type,
        ttl = Ttl.Persistent,
        memory = MemoryEstimate.Bytes(64),
    )

    private fun page(
        vararg keys: KeyMetadata,
        cursor: String = "0",
        stopped: ScanStop = ScanStop.COMPLETE,
    ) = ScanPage(
        cursor = ScanCursor.of(cursor),
        keys = keys.toList(),
        iterations = 1,
        stopped = stopped,
    )

    private fun FakeConnectionService.scans() = calls.filter { it.startsWith("scanKeys") }

    @Test
    fun `an empty page does not end a traversal`() = runTest {
        val service = service()
        // Two batches that matched nothing, and then the keys. This is the ordinary
        // shape of a selective MATCH against a large keyspace.
        service.scanPages += page(cursor = "17", stopped = ScanStop.PAGE_FULL)
        service.scanPages += page(cursor = "42", stopped = ScanStop.PAGE_FULL)
        service.scanPages += page(key("user:1"), key("user:2"))
        val model = model(service)

        model.show(id)
        advanceUntilIdle()

        assertEquals(3, service.scans().size)
        assertEquals(listOf("user:1", "user:2"), model.keys.map { it.key.text })
        assertEquals(ScanProgress.Complete, model.progress)
    }

    @Test
    fun `the automatic continuation stops rather than walking the whole keyspace`() = runTest {
        val service = service()
        // A pattern that matches nothing at all: every page is empty and the cursor
        // keeps advancing. Something has to stop, and it is the client.
        service.scanPage = page(cursor = "99", stopped = ScanStop.PAGE_FULL)
        val model = model(service)

        model.show(id)
        advanceUntilIdle()

        assertEquals(5, service.scans().size)
        val progress = assertIs<ScanProgress.More>(model.progress)
        // Said out loud, because a browser that has found nothing yet and a keyspace
        // with nothing in it are different facts.
        assertTrue(progress.empty)
    }

    @Test
    fun `Load more continues from the cursor the last page returned`() = runTest {
        val service = service()
        service.scanPages += page(key("a"), cursor = "512", stopped = ScanStop.PAGE_FULL)
        service.scanPages += page(key("b"))
        val model = model(service)
        model.show(id)
        advanceUntilIdle()

        model.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("scanKeys(0, null, null)", "scanKeys(512, null, null)"), service.scans())
        assertEquals(listOf("a", "b"), model.keys.map { it.key.text })
    }

    @Test
    fun `a key returned twice is listed once`() = runTest {
        val service = service()
        // Redis returns duplicates during a resizing traversal as a matter of course,
        // and the second sighting carries the newer TTL.
        service.scanPages += page(key("a"), key("b"), cursor = "8", stopped = ScanStop.PAGE_FULL)
        service.scanPages += page(key("b"), key("c"))
        val model = model(service)
        model.show(id)
        advanceUntilIdle()

        model.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), model.keys.map { it.key.text })
    }

    @Test
    fun `Refresh starts from zero and forgets what was deduplicated`() = runTest {
        val service = service()
        service.scanPages += page(key("a"), cursor = "8", stopped = ScanStop.PAGE_FULL)
        service.scanPages += page(key("b"))
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()

        service.scanPages += page(key("b"))
        model.refresh()
        advanceUntilIdle()

        // A key deleted since the last page would otherwise stay on screen forever:
        // nothing in a SCAN result says a key is gone.
        assertEquals(listOf("b"), model.keys.map { it.key.text })
        assertEquals("scanKeys(0, null, null)", service.scans().last())
    }

    @Test
    fun `a pattern is applied when it is searched, not as it is typed`() = runTest {
        val service = service()
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        val before = service.scans().size

        model.edit("user:*")
        advanceUntilIdle()
        assertEquals(before, service.scans().size)

        model.search()
        advanceUntilIdle()

        assertEquals("scanKeys(0, user:*, null)", service.scans().last())
    }

    @Test
    fun `a type filter restarts the traversal as a scan argument`() = runTest {
        val service = service()
        service.scanPage = page(key("a"))
        val model = model(service)
        model.show(id)
        advanceUntilIdle()

        model.filterBy(KeyType.HASH)
        advanceUntilIdle()

        assertEquals("scanKeys(0, null, hash)", service.scans().last())
    }

    @Test
    fun `clearing the filters drops both and walks the keyspace again`() = runTest {
        val service = service()
        service.scanPage = page(key("a"))
        val model = model(service)
        model.show(id)
        advanceUntilIdle()

        model.edit("user:*")
        model.search()
        model.filterBy(KeyType.HASH)
        advanceUntilIdle()
        assertTrue(model.filtered)

        model.clearFilters()
        advanceUntilIdle()

        // Both filters, and the box as well as the applied pattern — leaving the text
        // in it would make the button look like it had done nothing.
        assertEquals("scanKeys(0, null, null)", service.scans().last())
        assertEquals("", model.pattern)
        assertEquals("", model.appliedPattern)
        assertNull(model.typeFilter)
        assertFalse(model.filtered)
    }

    @Test
    fun `clearing filters that are not set reads nothing`() = runTest {
        val service = service()
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        val reads = service.scans().size

        model.clearFilters()
        advanceUntilIdle()

        assertEquals(reads, service.scans().size, "an unfiltered browser restarted its scan")
    }

    @Test
    fun `grouping and expanding read nothing`() = runTest {
        val service = service()
        service.scanPage = page(key("user:1:profile"), key("user:2:profile"), key("session:a"))
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        val reads = service.calls.size

        model.expandAll()
        model.toggleGrouping()
        model.toggleGrouping()
        model.collapseAll()
        advanceUntilIdle()

        // §3.4's rule, asserted the only way it can be: the tree has nothing to call.
        assertEquals(reads, service.calls.size)
        assertEquals(setOf("user", "session"), model.rows.filter { it.expandable }.map { it.label }.toSet())
    }

    @Test
    fun `a failed continuation keeps the keys already collected`() = runTest {
        val service = service()
        service.scanPages += page(key("a"), cursor = "8", stopped = ScanStop.PAGE_FULL)
        val model = model(service)
        model.show(id)
        advanceUntilIdle()

        service.nextFailure = IllegalStateException("connection reset")
        model.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("a"), model.keys.map { it.key.text })
        assertEquals("unexpected_error", model.failure?.code)
        // Still continuable: the cursor is valid and Load more is a retry.
        assertTrue(model.hasMore)
    }

    @Test
    fun `locking empties the browser`() = runTest {
        val service = service()
        service.scanPage = page(key("a"))
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        model.select(KeyRef("a".toByteArray()))

        model.clear()

        assertEquals(emptyList(), model.keys)
        assertEquals(null, model.selected)
        assertEquals(null, model.connectionId)
    }
}
