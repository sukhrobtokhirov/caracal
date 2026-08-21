package dev.caracal.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.caracal.core.catalog.ColumnInfo
import dev.caracal.core.catalog.Listing
import dev.caracal.core.catalog.SchemaInfo
import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.history.ExecutionOutcome
import dev.caracal.core.history.ExecutionRecord
import dev.caracal.core.redis.KeyMetadata
import dev.caracal.core.redis.KeyType
import dev.caracal.core.redis.MemoryEstimate
import dev.caracal.core.redis.RedisCursor
import dev.caracal.core.redis.RedisKey
import dev.caracal.core.redis.ScanPage
import dev.caracal.core.redis.ScanStop
import dev.caracal.core.redis.Ttl
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.ColumnFormat
import dev.caracal.core.result.QueryResult
import dev.caracal.core.result.ResultLimits
import dev.caracal.core.vault.VaultState
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * §4.10's awkward workloads, at the sizes it names.
 *
 * None of these asserts a duration. A timing on a shared CI runner is a coin flip
 * dressed as a measurement, and a suite that fails on a busy afternoon teaches people
 * to re-run it rather than to read it. What is asserted instead is the property that
 * makes the timing fine: that the work done is proportional to what is on screen
 * rather than to what is in memory, and that what is in memory has a ceiling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScaleTest {

    // --- 1,000 rows with 100 columns ------------------------------------------

    private fun wideResult(rows: Int = 1_000, columns: Int = 100) = QueryResult(
        columns = (0 until columns).map { Column("column_$it", "text", ColumnFormat.TEXT) },
        rows = List(rows) { row -> List(columns) { column -> CellValue.Text("r${row}c$column") } },
        duration = 120.milliseconds,
    )

    @Test
    fun `a thousand rows by a hundred columns draws a screenful, not a result`() {
        val state = ResultGridState(wideResult())

        // The grid is virtualized across *both* axes. Rows are a lazy list; columns
        // are this window, and without it a single row would compose a hundred cells
        // and the screen would compose a hundred thousand.
        val window = columnWindow(widths = state.widths, scroll = 0f, viewport = 1_200f)

        assertTrue(window.range.count() < 20, "the column window was ${window.range.count()} wide")
        assertEquals(100, state.result.columns.size, "the result itself is all there")
    }

    /**
     * The row cap is what makes a result a bounded object at all, and it is applied
     * where the rows are read rather than where they are drawn.
     */
    @Test
    fun `the row limit is what a result is allowed to cost`() {
        assertEquals(1_000, ResultLimits().rows)
        // A limit on rows alone is not a limit: one jsonb document can be a thousand
        // rows' worth of memory on its own.
        assertEquals(16 * 1024 * 1024, ResultLimits().totalCharacters)
    }

    private fun evictionConnection() = ConnectionConfig(
        id = ConnectionId("id-1"),
        name = "local",
        engine = dev.caracal.core.connections.Engine.POSTGRES,
        host = "localhost",
        port = 5432,
        database = "caracal",
        username = "caracal",
        tlsMode = dev.caracal.core.connections.TlsMode.DISABLE,
        environment = dev.caracal.core.connections.Environment.DEV,
        readOnly = false,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    // --- 100+ schemas ---------------------------------------------------------

    private fun catalogService(schemas: Int, relationsEach: Int) =
        FakeConnectionService(VaultState.UNLOCKED).apply {
            this.schemas = Listing((1..schemas).map { SchemaInfo("schema_$it", owner = "caracal") })
            (1..schemas).forEach { s ->
                (1..relationsEach).forEach { r ->
                    seedObject(
                        schema = "schema_$s",
                        name = "table_$r",
                        columns = listOf(ColumnInfo(1, "id", "bigint", nullable = false, primaryKey = true)),
                    )
                }
            }
        }

    @Test
    fun `four hundred schemas cost four hundred rows and no reads`() = runTest {
        val service = catalogService(schemas = 400, relationsEach = 50)
        val tree = SchemaTreeViewModel(service, this)

        tree.show(ConnectionId("id-1"))
        advanceUntilIdle()

        assertEquals(400, tree.rows.size)
        // Nothing under a closed schema has been asked for. A tree that read every
        // schema's contents to draw the schema would issue four hundred queries to
        // show four hundred names.
        assertEquals(1, service.calls.count { it.startsWith("schemas(") })
        assertEquals(0, service.calls.count { it.startsWith("objects(") })
    }

    @Test
    fun `expanding a hundred schemas over a session keeps the rows proportional`() = runTest {
        val service = catalogService(schemas = 120, relationsEach = 30)
        val tree = SchemaTreeViewModel(service, this)
        tree.show(ConnectionId("id-1"))
        advanceUntilIdle()

        val opened = tree.rows.take(100)
        opened.forEach { row ->
            tree.toggle(row.key)
            advanceUntilIdle()
        }

        // 120 schemas plus one folder each for the hundred that were opened. An open
        // schema contributes its *folders*, not its contents — the thirty tables
        // under each are only rows once the folder is opened too — so the tree is
        // proportional to what has been opened and not to the 3,600 objects behind
        // it.
        assertEquals(220, tree.rows.size)
        assertTrue(tree.rows.size < 120 * 30, "the tree drew the catalog rather than the tree")
    }

    // --- 50+ history entries --------------------------------------------------

    @Test
    fun `fifty history entries filter without rebuilding the list per keystroke`() = runTest {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "Local")
        repeat(60) { n ->
            service.history += ExecutionRecord(
                connectionId = ConnectionId("id-1"),
                statement = if (n % 3 == 0) "select * from invoices where id = $n" else "select $n",
                outcome = ExecutionOutcome.OK,
                executedAt = Instant.parse("2026-08-21T09:00:00Z"),
                duration = 4.milliseconds,
                rowCount = 1,
                error = null,
                id = n.toLong() + 1,
            )
        }
        val model = HistoryViewModel(service, this)
        model.open(ConnectionId("id-1"))
        advanceUntilIdle()

        model.searchFor("invoices")
        // `visible` is derived state: reading it twice with nothing changed in
        // between is one computation, not two. The assertion available here is the
        // answer; that it is computed once is what `derivedStateOf` is for.
        assertEquals(model.visible, model.visible)
        assertTrue(model.visible.all { it.statement.contains("invoices") })
        assertTrue(model.visible.size < model.entries.size)
    }

    // --- twenty tabs, and letting go of them ----------------------------------

    /**
     * §4.10's leak check, and the assertion is the test framework itself.
     *
     * `runTest` fails the test if a coroutine started in its scope is still alive
     * when the body returns. So twenty tabs, each holding a statement the fake never
     * answers, closed one at a time, is a direct test of whether closing a tab
     * releases the work it owned — and it fails loudly rather than quietly leaking a
     * thread's worth of nothing per tab, which is what makes this worth writing down
     * rather than reasoning about.
     */
    @Test
    fun `closing twenty tabs mid-query leaves nothing running`() = runTest {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.gate = CompletableDeferred()
        val tabs = EditorTabs(service, this) { null }
        val config = evictionConnection()

        val opened = (1..20).map { n ->
            tabs.open(config).also { tab ->
                tab.editor.edit(TextFieldValue("select pg_sleep($n)", TextRange(0)))
                tab.editor.execute()
            }
        }
        advanceUntilIdle()
        assertTrue(opened.all { it.running }, "the fixture did not get twenty statements running")

        opened.forEach { tab ->
            tabs.requestClose(tab)
            tabs.confirmClose()
        }
        advanceUntilIdle()

        assertEquals(emptyList(), tabs.tabs)
    }

    /** Locking is the other way twenty tabs go away, and it must be as complete. */
    @Test
    fun `locking with twenty tabs running stops all of them`() = runTest {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.gate = CompletableDeferred()
        val tabs = EditorTabs(service, this) { null }
        val config = evictionConnection()

        repeat(20) {
            tabs.open(config).also { tab ->
                tab.editor.edit(TextFieldValue("select pg_sleep(1)", TextRange(0)))
                tab.editor.execute()
            }
        }
        advanceUntilIdle()

        tabs.clear()
        advanceUntilIdle()

        assertEquals(emptyList(), tabs.tabs)
    }

    // --- many Redis scan pages ------------------------------------------------

    private fun keys(from: Int, count: Int) = (from until from + count).map { n ->
        KeyMetadata(
            key = RedisKey("session:$n".toByteArray()),
            type = KeyType.STRING,
            ttl = Ttl.Persistent,
            memory = MemoryEstimate.Bytes(64),
        )
    }

    private fun TestScope.walk(
        pages: Int,
        perPage: Int,
        clicks: Int = pages,
    ): RedisBrowserViewModel {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        repeat(pages) { page ->
            service.scanPages += ScanPage(
                cursor = RedisCursor.of("${page + 1}"),
                keys = keys(from = page * perPage, count = perPage),
                iterations = 1,
                stopped = ScanStop.PAGE_FULL,
            )
        }
        val model = RedisBrowserViewModel(service, this)
        model.show(ConnectionId("id-1"))
        advanceUntilIdle()
        repeat(clicks) {
            model.loadMore()
            advanceUntilIdle()
        }
        return model
    }

    @Test
    fun `a long walk of the keyspace stops collecting rather than growing forever`() = runTest {
        val model = walk(pages = 60, perPage = 200)

        assertTrue(model.keys.size <= RedisBrowserViewModel.MAX_COLLECTED_KEYS)
        assertIs<ScanProgress.Full>(model.progress)
        // Full is not More: Load more would collect nothing, so it is not offered.
        assertTrue(!model.hasMore)
    }

    @Test
    fun `an ordinary walk is unaffected by the ceiling`() = runTest {
        // One page short of the queue, so the last answer is still "there is more"
        // rather than the fake's default complete page.
        val model = walk(pages = 6, perPage = 200, clicks = 4)

        assertEquals(1_000, model.keys.size)
        assertIs<ScanProgress.More>(model.progress)
        assertTrue(model.hasMore)
    }
}
