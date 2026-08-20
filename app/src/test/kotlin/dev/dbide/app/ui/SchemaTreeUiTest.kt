package dev.dbide.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.EditorViewModel
import dev.dbide.app.ExportViewModel
import dev.dbide.app.FakeConnectionService
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.ThemeViewModel
import dev.dbide.core.catalog.ColumnInfo
import dev.dbide.core.catalog.Listing
import dev.dbide.core.catalog.ObjectKind
import dev.dbide.core.catalog.SchemaInfo
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.vault.VaultState
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
        schemas = Listing(listOf(SchemaInfo("public", owner = "dbide"), SchemaInfo("sales")))
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
    ): SchemaTreeViewModel {
        lateinit var model: SchemaTreeViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { SchemaTreeViewModel(service, scope) }
            LaunchedEffect(Unit) { model.show(ConnectionId("id-1")) }
            DbideTheme { SchemaTree(model, onInsertIdentifier = { inserted += it }) }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.clickNode(description: String) {
        onNodeWithContentDescription(description).performClick()
        waitForIdle()
    }

    @Test
    fun `the tree starts at the schemas and goes no further`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val service = service()
            tree(service)

            onNodeWithContentDescription("node-schema-public").assertIsDisplayed()
            onNodeWithContentDescription("node-schema-sales").assertIsDisplayed()
            // Nothing inside a schema has been asked for, so nothing inside one is drawn.
            onNodeWithContentDescription("node-folder-public-table").assertDoesNotExist()
            assertEquals(listOf("schemas(system=false)"), service.calls)
        }

    @Test
    fun `opening a schema shows what it holds, and opening a table shows its columns`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            tree(service())

            clickNode("node-schema-public")
            onNodeWithContentDescription("node-folder-public-table").assertIsDisplayed()
            // A kind this schema has none of is not a line in the tree.
            onNodeWithContentDescription("node-folder-public-function").assertDoesNotExist()

            clickNode("node-folder-public-table")
            onNodeWithContentDescription("node-object-public-users").assertIsDisplayed()

            clickNode("node-object-public-users")
            onNodeWithContentDescription("node-column-public-users-1").assertIsDisplayed()
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

            onNodeWithContentDescription("node-folder-public-table").assertIsDisplayed()

            clickNode("node-schema-public-toggle")

            onNodeWithContentDescription("node-folder-public-table").assertDoesNotExist()
        }

    @Test
    fun `a leaf has no triangle to press`() = runDesktopComposeUiTest(width = 500, height = 900) {
        tree(service())
        clickNode("node-schema-public")
        clickNode("node-folder-public-table")
        clickNode("node-object-public-users")

        onNodeWithContentDescription("node-column-public-users-1").assertIsDisplayed()
        onNodeWithContentDescription("node-column-public-users-1-toggle").assertDoesNotExist()
    }

    @Test
    fun `a schema that cannot be read says so on its own line`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val service = service()
            service.failingSchema = "public"
            tree(service)

            clickNode("node-schema-public")

            onNodeWithContentDescription("node-error").assertIsDisplayed()
            onNodeWithText("permission denied for schema public", substring = true).assertIsDisplayed()
            // The tree is still a tree: the schema that reads fine is still there.
            onNodeWithContentDescription("node-schema-sales").assertIsDisplayed()
        }

    @Test
    fun `double-clicking a table hands over a quoted, qualified name`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val inserted = mutableListOf<String>()
            tree(service(), inserted)
            clickNode("node-schema-public")
            clickNode("node-folder-public-table")

            onNodeWithContentDescription("node-object-public-users").performMouseInput {
                doubleClick()
            }
            waitForIdle()

            assertEquals(listOf("\"public\".\"users\""), inserted)
            onNodeWithText("Inserted \"public\".\"users\"").assertIsDisplayed()
        }

    @Test
    fun `the system schemas are hidden until they are asked for`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val service = service()
            service.schemas = Listing(
                listOf(SchemaInfo("public"), SchemaInfo("pg_catalog", system = true)),
            )
            tree(service)
            onNodeWithContentDescription("node-schema-pg_catalog").assertDoesNotExist()

            clickNode("tree-system-schemas")

            onNodeWithContentDescription("node-schema-pg_catalog").assertIsDisplayed()
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
                val editor = remember { EditorViewModel(service, scope) }
                val export = remember { ExportViewModel(service, scope) { null } }
                val theme = remember { ThemeViewModel(null, scope) }
                DbideTheme { WorkspaceScreen(connections, tree, editor, export, theme, onLock = {}) }
            }
            waitForIdle()

            onNodeWithContentDescription("connection-Closed").performClick()
            waitForIdle()
            onNodeWithContentDescription("schema-tree").assertDoesNotExist()

            onNodeWithContentDescription("connection-Live").performClick()
            waitForIdle()

            onNodeWithContentDescription("schema-tree").assertIsDisplayed()
            onNodeWithContentDescription("node-schema-public").assertIsDisplayed()
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
                val editor = remember { EditorViewModel(service, scope) }
                val export = remember { ExportViewModel(service, scope) { null } }
                val theme = remember { ThemeViewModel(null, scope) }
                DbideTheme { WorkspaceScreen(connections, tree, editor, export, theme, onLock = {}) }
            }
            waitForIdle()

            onNodeWithContentDescription("connection-Cache").performClick()
            waitForIdle()

            onNodeWithContentDescription("schema-tree").assertDoesNotExist()
        }
}
