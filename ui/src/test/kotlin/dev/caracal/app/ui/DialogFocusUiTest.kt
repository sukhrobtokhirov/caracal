package dev.caracal.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * §4.9's three promises about a modal window and the keyboard.
 *
 * Focus goes in when it opens, cannot get out while it is open, and comes back to
 * whatever opened it when it closes. Two of the three are Compose Desktop's — a
 * dialog is its own focus scope — and they are asserted here anyway, because "the
 * framework does it" is a claim with a version number attached and this is where the
 * next version's change to it should be noticed.
 */
@OptIn(ExperimentalTestApi::class)
class DialogFocusUiTest {

    /**
     * A dialog's own scope reports its focus independently of the window behind it,
     * so a plain `assertIsFocused` on an outer control is not evidence that focus
     * escaped. Naming the focused node in *each* scope is.
     */
    private fun ComposeUiTest.focusedIn(vararg tags: String): String? =
        tags.firstOrNull { tag ->
            runCatching { onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false)
        }

    private fun ComposeUiTest.shell(): () -> Boolean {
        var open by mutableStateOf(false)
        setContent {
            CaracalTheme {
                Column {
                    ToolButton(text = "Open", onClick = { open = true }, tag = "opener")
                    ToolButton(text = "Elsewhere", onClick = {}, tag = "elsewhere")
                    if (open) {
                        AppDialog(title = "Settings", tag = "dialog", onDismiss = { open = false }) {
                            Text("body")
                            ToolButton(text = "Inside", onClick = {}, tag = "first-control")
                        }
                    }
                }
            }
        }
        waitForIdle()
        return { open }
    }

    @Test
    fun `opening a dialog puts the keyboard inside it`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            shell()
            onNodeWithTag("opener").requestFocus()
            waitForIdle()

            onNodeWithTag("opener").performClick()
            waitForIdle()

            // Not "somewhere in the dialog" — the first control in it. A dialog that
            // opens with focus on nothing costs a keyboard user a Tab to arrive.
            assertEquals("first-control", focusedIn("first-control", "close-dialog"))
        }

    @Test
    fun `tab cycles within the dialog and never reaches the workspace`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            shell()
            onNodeWithTag("opener").performClick()
            waitForIdle()

            val visited = mutableSetOf<String>()
            repeat(6) {
                val here = focusedIn("first-control", "close-dialog")
                assertTrue(here != null, "focus left the dialog entirely")
                visited += here
                onNodeWithTag(here).performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
            }

            // Both of the dialog's controls, and only ever those two.
            assertEquals(setOf("first-control", "close-dialog"), visited)
        }

    @Test
    fun `closing a dialog gives the keyboard back to what opened it`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val open = shell()
            onNodeWithTag("opener").requestFocus()
            waitForIdle()
            onNodeWithTag("opener").performClick()
            waitForIdle()

            onNodeWithTag("close-dialog").performClick()
            waitForIdle()

            assertTrue(!open(), "the dialog did not close")
            onNodeWithTag("opener").assertIsFocused()
        }
}
