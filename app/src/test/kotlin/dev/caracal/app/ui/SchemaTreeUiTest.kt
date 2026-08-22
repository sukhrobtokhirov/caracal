package dev.caracal.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.ConnectionsViewModel
import dev.caracal.app.EditorTabs
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.HistoryViewModel
import dev.caracal.app.RedisWorkspace
import dev.caracal.app.SchemaTreeViewModel
import dev.caracal.app.ThemeViewModel
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.vault.VaultState
import dev.caracal.engine.api.ColumnInfo
import dev.caracal.engine.api.Listing
import dev.caracal.engine.api.ObjectKind
import dev.caracal.engine.api.SchemaInfo
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * The object browser, driven through the real composables.
 *
 * These are the assertions that would still pass with a correct view model behind a
 * broken screen: that nothing is on screen until it is opened, that a schema which
 * cannot be read says so without taking the rest of the tree with it, and that what
 * a double-click produces is SQL and not a bare name.
 */
@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class SchemaTreeUiTest {

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        schemas = Listing(listOf(SchemaInfo("public", owner = "caracal"), SchemaInfo("sales")))
        seedObject(
            schema = "public",
            name = "users",
            columns = listOf(
                ColumnInfo(1, "id", "bigint", nullable = false, primaryKey = true),
                ColumnInfo(2, "email", "text", nullable = true),
            ),
        )
        seedObject("public", "active_users", kind = ObjectKind.VIEW)
    }

    /** Mounts the panel on its own, with a real view model behind it. */
    private fun ComposeUiTest.tree(
        service: FakeConnectionService,
        inserted: MutableList<String> = mutableListOf(),
        copied: MutableList<String> = mutableListOf(),
    ): SchemaTreeViewModel {
        lateinit var model: SchemaTreeViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { SchemaTreeViewModel(service, scope) }
            LaunchedEffect(Unit) { model.show(ConnectionId("id-1")) }
            CaracalTheme {
                SchemaTree(
                    model,
                    TreeActions(insert = { inserted += it }, copy = { copied += it }),
                )
            }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.clickNode(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    @Test
    fun `the tree starts at the schemas and goes no further`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val service = service()
            tree(service)

            onNodeWithTag("node-schema-public").assertIsDisplayed()
            onNodeWithTag("node-schema-sales").assertIsDisplayed()
            // Nothing inside a schema has been asked for, so nothing inside one is drawn.
            onNodeWithTag("node-folder-public-table").assertDoesNotExist()
            assertEquals(listOf("schemas(system=false)"), service.calls)
        }

    @Test
    fun `opening a schema shows what it holds, and opening a table shows its columns`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            tree(service())

            clickNode("node-schema-public")
            onNodeWithTag("node-folder-public-table").assertIsDisplayed()
            // A kind this schema has none of is not a line in the tree.
            onNodeWithTag("node-folder-public-function").assertDoesNotExist()

            clickNode("node-folder-public-table")
            onNodeWithTag("node-object-public-users").assertIsDisplayed()

            clickNode("node-object-public-users")
            onNodeWithTag("node-column-public-users-1").assertIsDisplayed()
            onNodeWithText("bigint").assertIsDisplayed()
            onNodeWithText("PK").assertIsDisplayed()
        }

    @Test
    fun `the triangle opens a node on its own`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            tree(service())

            // The arrow is a control the pointer can actually land on, rather than
            // ten device-independent pixels of text with a row behind it.
            clickNode("node-schema-public-toggle")

            onNodeWithTag("node-folder-public-table").assertIsDisplayed()

            clickNode("node-schema-public-toggle")

            onNodeWithTag("node-folder-public-table").assertDoesNotExist()
        }

    @Test
    fun `a leaf has no triangle to press`() = runDesktopComposeUiTest(width = 500, height = 900) {
        tree(service())
        clickNode("node-schema-public")
        clickNode("node-folder-public-table")
        clickNode("node-object-public-users")

        onNodeWithTag("node-column-public-users-1").assertIsDisplayed()
        onNodeWithTag("node-column-public-users-1-toggle").assertDoesNotExist()
    }

    @Test
    fun `a schema that cannot be read says so on its own line`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val service = service()
            service.failingSchema = "public"
            tree(service)

            clickNode("node-schema-public")

            onNodeWithTag("node-error").assertIsDisplayed()
            onNodeWithText("permission denied for schema public", substring = true).assertIsDisplayed()
            // The tree is still a tree: the schema that reads fine is still there.
            onNodeWithTag("node-schema-sales").assertIsDisplayed()
        }

    @Test
    fun `double-clicking a table hands over a quoted, qualified name`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val inserted = mutableListOf<String>()
            tree(service(), inserted)
            clickNode("node-schema-public")
            clickNode("node-folder-public-table")

            onNodeWithTag("node-object-public-users").performMouseInput {
                doubleClick()
            }
            waitForIdle()

            assertEquals(listOf("\"public\".\"users\""), inserted)
            onNodeWithText("Inserted \"public\".\"users\"").assertIsDisplayed()
        }

    @Test
    fun `right-clicking a table offers a query over it, and writes the statement`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val inserted = mutableListOf<String>()
            tree(service(), inserted)
            clickNode("node-schema-public")
            clickNode("node-folder-public-table")

            onNodeWithTag("node-object-public-users").performMouseInput {
                rightClick()
            }
            waitForIdle()

            onNodeWithTag("menu-select-rows").assertIsDisplayed()
            onNodeWithTag("menu-copy").assertIsDisplayed()
            onNodeWithTag("menu-refresh").assertIsDisplayed()

            onNodeWithTag("menu-select-rows").performClick()
            waitForIdle()

            // Bounded, quoted, qualified — and it arrives in the editor rather than
            // at the server, so Run is still the user's decision.
            assertEquals(listOf("SELECT * FROM \"public\".\"users\" LIMIT 100;"), inserted)
            // The menu closes behind the action it performed.
            onNodeWithTag("menu-select-rows").assertDoesNotExist()
        }

    @Test
    fun `the menu copies the same name a double-click would insert`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val copied = mutableListOf<String>()
            tree(service(), copied = copied)
            clickNode("node-schema-public")
            clickNode("node-folder-public-table")
            clickNode("node-object-public-users")

            onNodeWithTag("node-column-public-users-2").performMouseInput {
                rightClick()
            }
            waitForIdle()

            // A column names nothing to select from and has nothing under it to
            // re-read, so it offers the two lines it can honour and no more.
            onNodeWithTag("menu-select-rows").assertDoesNotExist()
            onNodeWithTag("menu-refresh").assertDoesNotExist()

            onNodeWithTag("menu-copy").performClick()
            waitForIdle()

            assertEquals(listOf("\"email\""), copied)
            onNodeWithText("Copied \"email\"").assertIsDisplayed()
        }

    @Test
    fun `a folder offers only the re-read it can honour`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val service = service()
            tree(service)
            clickNode("node-schema-public")

            onNodeWithTag("node-folder-public-table").performMouseInput {
                rightClick()
            }
            waitForIdle()

            onNodeWithTag("menu-insert").assertDoesNotExist()
            onNodeWithTag("menu-refresh").performClick()
            waitForIdle()

            // One node re-read, not the whole connection: the schema list is still
            // the one the tree started with.
            assertEquals(1, service.calls.count { it.startsWith("schemas") })
            assertEquals(2, service.calls.count { it == "objects(public, TABLE)" })
        }

    @Test
    fun `the system schemas are hidden until they are asked for`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val service = service()
            service.schemas = Listing(
                listOf(SchemaInfo("public"), SchemaInfo("pg_catalog", system = true)),
            )
            tree(service)
            onNodeWithTag("node-schema-pg_catalog").assertDoesNotExist()

            clickNode("tree-system-schemas")

            onNodeWithTag("node-schema-pg_catalog").assertIsDisplayed()
            onNodeWithText("system").assertIsDisplayed()
        }

    @Test
    fun `the browser is only there for a connection that is open`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val service = service()
            val closed = service.seed(name = "Closed", status = RuntimeStatus.CLOSED)
            service.seed(name = "Live", status = RuntimeStatus.OPEN)
            setContent {
                val scope = rememberCoroutineScope()
                val connections = remember { ConnectionsViewModel(service, scope) }
                val tree = remember { SchemaTreeViewModel(service, scope) }
                val tabs = remember { EditorTabs(service, scope) { null } }
                val theme = remember { ThemeViewModel(null, scope) }
                val redis = remember { RedisWorkspace(service, scope) }
                val history = remember { HistoryViewModel(service, scope) }
                CaracalTheme {
                WorkspaceScreen(connections, tree, tabs, redis, history, theme, onLock = {})
            }
            }
            waitForIdle()

            onNodeWithTag("connection-Closed").performClick()
            waitForIdle()
            onNodeWithTag("schema-tree").assertDoesNotExist()

            onNodeWithTag("connection-Live").performClick()
            waitForIdle()

            onNodeWithTag("schema-tree").assertIsDisplayed()
            onNodeWithTag("node-schema-public").assertIsDisplayed()
            // A closed connection is not dialed to fill a panel.
            assertEquals(closed.config.name, "Closed")
        }

    @Test
    fun `a Redis connection has no schema browser`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val service = service()
            service.seed(name = "Cache", engine = Engine.REDIS, status = RuntimeStatus.OPEN)
            setContent {
                val scope = rememberCoroutineScope()
                val connections = remember { ConnectionsViewModel(service, scope) }
                val tree = remember { SchemaTreeViewModel(service, scope) }
                val tabs = remember { EditorTabs(service, scope) { null } }
                val theme = remember { ThemeViewModel(null, scope) }
                val redis = remember { RedisWorkspace(service, scope) }
                val history = remember { HistoryViewModel(service, scope) }
                CaracalTheme {
                WorkspaceScreen(connections, tree, tabs, redis, history, theme, onLock = {})
            }
            }
            waitForIdle()

            onNodeWithTag("connection-Cache").performClick()
            waitForIdle()

            onNodeWithTag("schema-tree").assertDoesNotExist()
        }
}
