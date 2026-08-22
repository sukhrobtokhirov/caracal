package dev.caracal.app

import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.vault.VaultState
import dev.caracal.engine.api.FieldEntry
import dev.caracal.engine.api.IndexedElement
import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.MemoryEstimate
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.ScoredMember
import dev.caracal.engine.api.StreamEntry
import dev.caracal.engine.api.TextValue
import dev.caracal.engine.api.Ttl
import dev.caracal.engine.api.ValuePage
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * How a value is paged, and what the pane does when the key changes underneath it.
 *
 * The continuation assertions are the important half. Each of the six types resumes
 * differently — a cursor, a byte offset, a rank, an exclusive stream ID — and getting
 * one of them wrong does not produce an error: it produces a **Show more** that
 * silently re-reads the first page forever, or one that skips an entry. So every test
 * here reads what the *second* request actually contained.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RedisValueViewModelTest {

    private val id = ConnectionId("id-1")
    private val key = KeyRef("k".toByteArray())

    private fun service() = FakeConnectionService(VaultState.UNLOCKED)

    private fun TestScope.model(service: FakeConnectionService) = RedisValueViewModel(service, this)

    private fun metadata(type: KeyType?) = KeyMetadata(
        key = key,
        type = type,
        ttl = Ttl.Persistent,
        memory = MemoryEstimate.Bytes(128),
    )

    private fun text(value: String) = TextValue.Utf8(value, byteCount = value.length)

    /** Opens [key] against a service already primed with its metadata and first page. */
    private fun TestScope.opened(
        service: FakeConnectionService,
        type: KeyType,
    ): RedisValueViewModel {
        service.keyMetadata[key] = metadata(type)
        val model = model(service)
        model.show(id)
        model.open(key)
        advanceUntilIdle()
        return model
    }

    @Test
    fun `opening a key reads its metadata before its value`() = runTest {
        val service = service()
        service.valuePage = ValuePage.Fields(key, emptyList(), ScanCursor.START, complete = true)
        opened(service, KeyType.HASH)

        // §3.5: the metadata is read fresh rather than taken from the scan page, so a
        // key with five seconds left shows a countdown rather than a fiction.
        assertEquals(listOf("keyMetadata", "readValue(hash)"), service.calls)
    }

    @Test
    fun `a hash continues from the cursor the last page returned`() = runTest {
        val service = service()
        service.valuePages += ValuePage.Fields(
            key = key,
            entries = listOf(FieldEntry(text("a"), text("1"))),
            cursor = ScanCursor.of("17"),
            complete = false,
        )
        service.valuePages += ValuePage.Fields(
            key = key,
            entries = listOf(FieldEntry(text("b"), text("2"))),
            cursor = ScanCursor.START,
            complete = true,
        )
        val model = opened(service, KeyType.HASH)

        model.more()
        advanceUntilIdle()

        assertEquals("17", service.valueRequests.last().cursor.value)
        val value = assertIs<LoadedValue.Fields>(assertIs<ValueState.Ready>(model.state).value)
        // Added to, not replaced: paging is what keeps what you were reading readable.
        assertEquals(listOf("a", "b"), value.entries.map { it.field.text })
        assertTrue(value.complete)
    }

    @Test
    fun `a set continues from its own cursor`() = runTest {
        val service = service()
        service.valuePages += ValuePage.Members(
            key = key,
            members = listOf(text("one")),
            cursor = ScanCursor.of("64"),
            complete = false,
        )
        service.valuePages += ValuePage.Members(key, listOf(text("two")), ScanCursor.START, true)
        val model = opened(service, KeyType.SET)

        model.more()
        advanceUntilIdle()

        assertEquals("64", service.valueRequests.last().cursor.value)
        assertEquals(2, assertIs<ValueState.Ready>(model.state).value.loaded)
    }

    @Test
    fun `a sorted set continues by rank`() = runTest {
        val service = service()
        service.valuePages += ValuePage.Scored(
            key = key,
            members = listOf(ScoredMember(text("a"), "1.5")),
            offset = 0,
            nextOffset = 100,
            total = 200,
            complete = false,
        )
        service.valuePages += ValuePage.Scored(
            key = key,
            members = listOf(ScoredMember(text("b"), "2")),
            offset = 100,
            nextOffset = null,
            total = 200,
            complete = true,
        )
        val model = opened(service, KeyType.ZSET)

        model.more()
        advanceUntilIdle()

        assertEquals(100L, service.valueRequests.last().offset)
        val value = assertIs<LoadedValue.Scored>(assertIs<ValueState.Ready>(model.state).value)
        // The server's own text for the score, never a reparsed double.
        assertEquals(listOf("1.5", "2"), value.members.map { it.score })
    }

    @Test
    fun `a list continues by index and keeps the indices it was given`() = runTest {
        val service = service()
        service.valuePages += ValuePage.Elements(
            key = key,
            elements = listOf(IndexedElement(0, text("first"))),
            offset = 0,
            nextOffset = 1,
            total = 2,
            complete = false,
        )
        service.valuePages += ValuePage.Elements(
            key = key,
            elements = listOf(IndexedElement(1, text("second"))),
            offset = 1,
            nextOffset = null,
            total = 2,
            complete = true,
        )
        val model = opened(service, KeyType.LIST)

        model.more()
        advanceUntilIdle()

        assertEquals(1L, service.valueRequests.last().offset)
        val value = assertIs<LoadedValue.Elements>(assertIs<ValueState.Ready>(model.state).value)
        assertEquals(listOf(0L, 1L), value.elements.map { it.index })
    }

    @Test
    fun `a stream continues past the last entry without repeating it`() = runTest {
        val service = service()
        service.valuePages += ValuePage.Entries(
            key = key,
            entries = listOf(StreamEntry("1700000000000-0", listOf(FieldEntry(text("a"), text("1"))))),
            nextId = "1700000000000-0",
            complete = false,
        )
        service.valuePages += ValuePage.Entries(key, emptyList(), nextId = null, complete = true)
        val model = opened(service, KeyType.STREAM)

        model.more()
        advanceUntilIdle()

        assertEquals("1700000000000-0", service.valueRequests.last().fromId)
    }

    @Test
    fun `a string continues by byte offset and joins its windows`() = runTest {
        val service = service()
        service.valuePages += ValuePage.Text(
            key = key,
            content = TextValue.Utf8("hello ", byteCount = 11, truncated = true),
            offset = 0,
            nextOffset = 6,
            length = 11,
            complete = false,
        )
        service.valuePages += ValuePage.Text(
            key = key,
            content = TextValue.Utf8("world", byteCount = 11, truncated = true),
            offset = 6,
            nextOffset = null,
            length = 11,
            complete = true,
        )
        val model = opened(service, KeyType.STRING)

        model.more()
        advanceUntilIdle()

        assertEquals(6L, service.valueRequests.last().offset)
        val value = assertIs<LoadedValue.Text>(assertIs<ValueState.Ready>(model.state).value)
        assertEquals("hello world", value.text)
        assertEquals(11, value.loadedBytes)
    }

    @Test
    fun `a window that did not decode makes the value binary rather than lossy text`() = runTest {
        val service = service()
        service.valuePage = ValuePage.Text(
            key = key,
            content = TextValue.Binary("ff00", byteCount = 2),
            offset = 0,
            nextOffset = null,
            length = 2,
            complete = true,
        )
        val model = opened(service, KeyType.STRING)

        val value = assertIs<LoadedValue.Text>(assertIs<ValueState.Ready>(model.state).value)
        assertTrue(value.binary)
        assertNull(value.text)
        assertEquals("ff00", value.hex)
        // Nothing is parsed as JSON that is not complete, decodable text.
        assertNull(model.json)
    }

    @Test
    fun `a complete JSON string is offered as a tree, and an incomplete one is not`() = runTest {
        val service = service()
        service.valuePages += ValuePage.Text(
            key = key,
            content = TextValue.Utf8("""{"a":1}""", byteCount = 14, truncated = true),
            offset = 0,
            nextOffset = 7,
            length = 14,
            complete = false,
        )
        service.valuePages += ValuePage.Text(
            key = key,
            content = TextValue.Utf8(""",{"b":2}""", byteCount = 14, truncated = false),
            offset = 7,
            nextOffset = null,
            length = 14,
            complete = true,
        )
        val model = opened(service, KeyType.STRING)

        // Half a document can parse as something it is not. Detection waits.
        assertNull(model.json)

        model.more()
        advanceUntilIdle()

        // And the joined-up text is still not one document, so it stays raw. This is
        // the silent fallback §3.7 asks for rather than a complaint.
        assertNull(model.json)
        assertEquals(TextView.RAW, model.textView)
    }

    @Test
    fun `JSON is detected and pretty-printed once the whole string is read`() = runTest {
        val service = service()
        val document = """{"id":42,"tags":["a","b"]}"""
        service.valuePage = ValuePage.Text(
            key = key,
            content = TextValue.Utf8(document, byteCount = document.length),
            offset = 0,
            nextOffset = null,
            length = document.length,
            complete = true,
        )
        val model = opened(service, KeyType.STRING)

        val json = assertNotNull(model.json)
        assertTrue(json.contains("\n"))
        // The number is copied across as text: 42 stays 42, and a jsonb-scale numeric
        // would keep every digit it arrived with.
        assertTrue(json.contains("\"id\": 42"))

        model.showJson()
        assertEquals(TextView.JSON, model.textView)
        model.showRaw()
        assertEquals(TextView.RAW, model.textView)
    }

    @Test
    fun `a key that expired is missing rather than failed`() = runTest {
        val service = service()
        service.keyMetadata[key] = KeyMetadata(key, type = null, ttl = Ttl.Gone, memory = MemoryEstimate.Absent)
        val model = model(service)
        model.show(id)

        model.open(key)
        advanceUntilIdle()

        assertIs<ValueState.Missing>(model.state)
        // The value was never asked for: there is nothing to read.
        assertEquals(listOf("keyMetadata"), service.calls)
    }

    @Test
    fun `a key that changed type reloads as what it is now`() = runTest {
        val service = service()
        service.metadataPages += metadata(KeyType.STRING)
        service.metadataPages += metadata(KeyType.LIST)
        service.nextValueFailure = DbException(DbError.KeyTypeChanged(expected = "string", actual = "list"))
        service.valuePages += ValuePage.Elements(
            key = key,
            elements = listOf(IndexedElement(0, text("first"))),
            offset = 0,
            nextOffset = null,
            total = 1,
            complete = true,
        )
        val model = model(service)
        model.show(id)

        model.open(key)
        advanceUntilIdle()

        assertIs<LoadedValue.Elements>(assertIs<ValueState.Ready>(model.state).value)
        // Said once, after the fact: the viewer has already reloaded, so this is a note
        // about what happened rather than a question about what to do.
        assertTrue(model.notice.orEmpty().contains("list"))
    }

    @Test
    fun `a key that vanished mid-read is missing, not a WRONGTYPE`() = runTest {
        val service = service()
        service.keyMetadata[key] = metadata(KeyType.HASH)
        service.nextValueFailure = DbException(DbError.KeyTypeChanged(expected = "hash", actual = null))
        val model = model(service)
        model.show(id)

        model.open(key)
        advanceUntilIdle()

        assertIs<ValueState.Missing>(model.state)
        assertNotNull(model.notice)
    }

    @Test
    fun `an unviewable type says so instead of opening a viewer`() = runTest {
        val service = service()
        service.keyMetadata[key] = KeyMetadata(
            key = key,
            type = null,
            ttl = Ttl.Persistent,
            memory = MemoryEstimate.Unavailable,
            unsupportedType = "ReJSON-RL",
        )
        val model = model(service)
        model.show(id)

        model.open(key)
        advanceUntilIdle()

        assertEquals("ReJSON-RL", assertIs<ValueState.Unsupported>(model.state).reported)
    }

    @Test
    fun `Show more does nothing once the value is complete`() = runTest {
        val service = service()
        service.valuePage = ValuePage.Members(key, listOf(text("only")), ScanCursor.START, complete = true)
        val model = opened(service, KeyType.SET)
        val reads = service.calls.size

        model.more()
        advanceUntilIdle()

        assertEquals(reads, service.calls.size)
    }
}
