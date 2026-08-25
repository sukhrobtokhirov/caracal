package dev.caracal.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.SchemaTreeViewModel
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.vault.VaultState
import dev.caracal.engine.api.ColumnInfo
import dev.caracal.engine.api.Listing
import dev.caracal.engine.api.ObjectKind
import dev.caracal.engine.api.SchemaInfo
import org.junit.jupiter.api.Test

/**
 * §4.9's keyboard, in the two places it has to behave like something people already
 * know: a tree and a tab strip.
 *
 * Up and down are Compose's — a clickable row is focusable, and arrow keys move
 * focus between focusable siblings — so they are asserted rather than implemented.
 * Right and left are not, and a tree that cannot be opened without a pointer is a
 * tree a keyboard user cannot use at all.
 */
@OptIn(ExperimentalTestApi::class)
class KeyboardUiTest {

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        schemas = Listing(listOf(SchemaInfo("public", owner = "caracal"), SchemaInfo("sales")))
        seedObject(
            schema = "public",
            name = "users",
            columns = listOf(ColumnInfo(1, "id", "bigint", nullable = false, primaryKey = true)),
        )
        seedObject("public", "active_users", kind = ObjectKind.VIEW)
    }

    private fun ComposeUiTest.tree(service: FakeConnectionService): SchemaTreeViewModel {
        lateinit var model: SchemaTreeViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { SchemaTreeViewModel(service, scope) }
            LaunchedEffect(Unit) { model.show(ConnectionId("id-1")) }
            CaracalTheme { SchemaTree(model, TreeActions(insert = {}, copy = {})) }
        }
        waitForIdle()
        return model
    }

    private fun ComposeUiTest.press(tag: String, key: Key) {
        onNodeWithTag(tag).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    @Test
    fun `right opens a schema and left closes it again`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            tree(service())

            onNodeWithTag("node-schema-public").requestFocus()
            waitForIdle()
            onNodeWithTag("node-schema-public").assertIsFocused()

            press("node-schema-public", Key.DirectionRight)
            // The schema's contents are on screen, and no pointer was involved.
            onNodeWithTag("node-folder-public-table").assertIsDisplayed()

            press("node-schema-public", Key.DirectionLeft)
            onNodeWithTag("node-folder-public-table").assertDoesNotExist()
        }

    @Test
    fun `right on an already open node leaves it open`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            tree(service())

            onNodeWithTag("node-schema-public").requestFocus()
            waitForIdle()
            press("node-schema-public", Key.DirectionRight)
            onNodeWithTag("node-folder-public-table").assertIsDisplayed()

            // The key is not swallowed a second time — it falls through to focus
            // movement, which is what walks into the children it just revealed.
            press("node-schema-public", Key.DirectionRight)
            onNodeWithTag("node-folder-public-table").assertIsDisplayed()
        }

    @Test
    fun `down moves to the next row`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            tree(service())

            onNodeWithTag("node-schema-public").requestFocus()
            waitForIdle()
            press("node-schema-public", Key.DirectionDown)

            onNodeWithTag("node-schema-sales").assertIsFocused()
        }
}
