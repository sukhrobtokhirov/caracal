package dev.caracal.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.EditorTabs
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.POSTGRES
import dev.caracal.app.networkConfig
import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.TlsMode
import dev.caracal.core.result.CellValue
import dev.caracal.core.result.Column
import dev.caracal.core.result.ColumnFormat
import dev.caracal.core.result.QueryResult
import dev.caracal.core.vault.VaultState
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test

/**
 * The tab strip, driven through the real composables.
 *
 * The assertions a view-model test cannot make: that the strip says which tab is
 * still running and which one holds unsaved work, that the pane in front of the user
 * is the tab they clicked and carries that tab's own result, and that the two ways to
 * lose a tab — a script and a running statement — both stop and ask.
 */
@OptIn(ExperimentalTestApi::class)
class QueryTabsUiTest {

    private fun connection(id: String = "id-1", name: String = "local") = networkConfig(
        id = ConnectionId(id),
        name = name,
        engineId = POSTGRES,
        host = "localhost",
        port = 5432,
        database = "caracal",
        username = "caracal",
        tlsMode = TlsMode.DISABLE,
        environment = Environment.DEV,
        readOnly = false,
        color = null,
        createdAt = Instant.parse("2026-08-21T10:00:00Z"),
    )

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        queryResult = QueryResult(
            columns = listOf(Column("n", "int8", ColumnFormat.NUMBER)),
            rows = listOf(listOf(CellValue.Integer(7))),
            duration = 4.milliseconds,
        )
    }

    private val local = connection()
    private val staging = connection(id = "id-2", name = "staging")

    private var movedTo: ConnectionId? = null

    /** The query side of one open connection, with [others] to move a tab to. */
    private fun ComposeUiTest.workspace(
        service: FakeConnectionService,
        others: List<ConnectionConfig> = emptyList(),
    ): EditorTabs {
        lateinit var tabs: EditorTabs
        setContent {
            val scope = rememberCoroutineScope()
            tabs = remember { EditorTabs(service, scope) { null }.also { it.show(local) } }
            CaracalTheme {
                QueryWorkspace(
                    tabs = tabs,
                    connection = local,
                    others = others,
                    onMoved = { movedTo = it },
                    onCopy = {},
                )
            }
        }
        waitForIdle()
        return tabs
    }

    private fun ComposeUiTest.type(sql: String) {
        onNodeWithTag("editor-text").performTextInput(sql)
        waitForIdle()
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    // --- Released results -----------------------------------------------------

    /**
     * §4.10's eviction, from the side that matters: what a person sees when they come
     * back to a tab whose grid the window let go.
     *
     * The requirement is not that the result survives — it is that its absence is
     * explained and reversible. A tab that quietly said "No result yet" would be a
     * tab that looks like it was never used, and its owner would go looking for work
     * they had not lost.
     */
    @Test
    fun `a released tab says what happened and offers to run it again`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val service = service()
            val tabs = workspace(service)

            val first = tabs.tabs.single()
            type("select invoices;")
            click("editor-run")

            // Enough other tabs that the first one is past the cap.
            repeat(12) { tabs.open(local) }
            waitForIdle()

            tabs.activate(first)
            waitForIdle()

            onNodeWithTag("query-released").assertIsDisplayed()
            // The script is untouched: it is the same text, in the same editor.
            onNodeWithTag("editor-text").assertTextContains("select invoices;")

            click("rerun-released")
            onNodeWithTag("query-released").assertDoesNotExist()
            onNodeWithTag("grid-status").assertIsDisplayed()
        }

    // --- Switching ------------------------------------------------------------

    @Test
    fun `the strip names each tab by its script, and the pane follows the click`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val tabs = workspace(service())
            type("select invoices;")

            onNodeWithTag("editor-tab-0").assertTextEquals("select invoices")

            click("editor-tab-new")
            onNodeWithTag("editor-tab-1").assertTextEquals("Untitled")
            type("select ledger;")
            onNodeWithTag("editor-tab-1").assertTextEquals("select ledger")

            // Back to the first, which still holds what was typed into it.
            click("editor-tab-0")
            assertEquals("select invoices;", tabs.active(local.id)?.editor?.text?.text)
            onNodeWithTag("editor-text").assertTextEquals("select invoices;")
        }

    @Test
    fun `a result belongs to the tab that asked for it`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            workspace(service())
            type("select 7;")
            click("editor-run")

            onNodeWithTag("grid-cell-0-0").assertTextEquals("7")

            click("editor-tab-new")
            // A fresh tab has run nothing, and shows that rather than the other tab's
            // grid.
            onNodeWithTag("query-idle").assertIsDisplayed()

            click("editor-tab-0")
            onNodeWithTag("grid-cell-0-0").assertTextEquals("7")
        }

    @Test
    fun `a statement stays with its tab while another one is in front`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val service = service()
            service.gate = CompletableDeferred()
            workspace(service)
            type("select pg_sleep(30);")
            click("editor-run")
            onNodeWithTag("query-running").assertIsDisplayed()

            click("editor-tab-new")

            // The tab it belongs to says so from the strip, while the pane in front is
            // the new tab's own empty one.
            onNodeWithTag("editor-tab-running-0").assertIsDisplayed()
            onNodeWithTag("query-idle").assertIsDisplayed()

            click("editor-tab-0")
            onNodeWithTag("query-running").assertIsDisplayed()
            click("editor-cancel")
            onNodeWithTag("query-cancelled").assertIsDisplayed()
            service.gate?.complete(Unit)
        }

    @Test
    fun `an unsaved tab is marked in the strip`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            workspace(service())

            onNodeWithTag("editor-tab-unsaved-0", useUnmergedTree = true).assertDoesNotExist()
            type("select 1;")
            onNodeWithTag("editor-tab-unsaved-0", useUnmergedTree = true).assertIsDisplayed()
        }

    // --- Closing --------------------------------------------------------------

    @Test
    fun `closing a tab that holds a script asks, and keeping it keeps everything`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val tabs = workspace(service())
            type("select invoices;")

            click("editor-tab-close-0")
            onNodeWithTag("close-tab-confirmation").assertIsDisplayed()

            click("cancel-close-tab")
            assertEquals(1, tabs.tabs.size)
            assertEquals("select invoices;", tabs.active(local.id)?.editor?.text?.text)

            click("editor-tab-close-0")
            click("confirm-close-tab")
            assertEquals(emptyList(), tabs.tabs)
        }

    @Test
    fun `closing a tab with a statement running offers to cancel it`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val service = service()
            service.gate = CompletableDeferred()
            val tabs = workspace(service)
            type("select pg_sleep(30);")
            click("editor-run")

            click("editor-tab-close-0")
            onNodeWithTag("close-tab-confirmation").assertIsDisplayed()
            click("confirm-close-tab")

            assertEquals(emptyList(), tabs.tabs)
            service.gate?.complete(Unit)
        }

    @Test
    fun `the last tab closing leaves somewhere to start again`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val tabs = workspace(service())

            click("editor-tab-close-0")

            // Nothing to ask about — the tab was empty — and the pane says what to do
            // rather than going blank.
            onNodeWithTag("query-no-tabs").assertIsDisplayed()
            click("query-new-tab")
            assertEquals(1, tabs.tabs.size)
            onNodeWithTag("sql-editor").assertIsDisplayed()
        }

    // --- The tab's own menu ---------------------------------------------------

    @Test
    fun `duplicating gives a second tab holding the same script`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val tabs = workspace(service())
            type("select invoices;")

            onNodeWithTag("editor-tab-0").performMouseInput { rightClick() }
            waitForIdle()
            click("tab-duplicate")

            assertEquals(
                listOf("select invoices;", "select invoices;"),
                tabs.tabs.map { it.editor.text.text },
            )
            // The copy is the one in front, and it counts as unsaved from the start.
            assertTrue(tabs.active(local.id)?.dirty == true)
        }

    @Test
    fun `a tab can be moved to another open connection, and the workspace follows it`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val tabs = workspace(service(), others = listOf(staging))
            type("select invoices;")

            onNodeWithTag("editor-tab-0").performMouseInput { rightClick() }
            waitForIdle()
            click("tab-move-staging")

            assertEquals(staging.id, tabs.tabs.single().connectionId)
            assertEquals(staging.id, movedTo)
            // Gone from this connection's strip, which is why the workspace was asked
            // to follow it.
            onNodeWithTag("query-no-tabs").assertIsDisplayed()
        }

    @Test
    fun `a tab running a statement is not offered a move`() =
        runDesktopComposeUiTest(width = 1100, height = 800) {
            val service = service()
            service.gate = CompletableDeferred()
            workspace(service, others = listOf(staging))
            type("select pg_sleep(30);")
            click("editor-run")

            onNodeWithTag("editor-tab-0").performMouseInput { rightClick() }
            waitForIdle()

            onNodeWithTag("tab-move-staging").assertIsNotEnabled()
            service.gate?.complete(Unit)
        }
}
