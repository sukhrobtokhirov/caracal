package dev.dbide.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.dbide.app.ConnectionsViewModel
import dev.dbide.app.EditorTabs
import dev.dbide.app.FakeConnectionService
import dev.dbide.app.HistoryViewModel
import dev.dbide.app.RedisWorkspace
import dev.dbide.app.SchemaTreeViewModel
import dev.dbide.app.Shortcuts
import dev.dbide.app.ThemeViewModel
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.vault.MetadataStore
import dev.dbide.core.vault.VaultState
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * §4.8, from the outside: the theme is one decision the whole window is made of.
 *
 * The palettes themselves are checked arithmetically in [ThemeContrastTest]. What
 * only a composition can answer is whether choosing a theme actually reaches
 * everything — a `MaterialTheme` swapped underneath a dialog that had already read
 * its colours is the bug this is looking for — and whether the three modes really
 * are three and not two with a spare.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeUiTest {

    /** Records what a composable *inside* the theme actually reads. */
    private class Observed {
        var surface: Color = Color.Unspecified
        var onSurface: Color = Color.Unspecified
        var editorKeyword: Color = Color.Unspecified
    }

    @Composable
    private fun Observed.record() {
        surface = MaterialTheme.colorScheme.surface
        onSurface = MaterialTheme.colorScheme.onSurface
        editorKeyword = Dbide.colors.syntax.keyword
    }

    @Test
    fun `each theme is the palette it names`() = runDesktopComposeUiTest {
        val dark = Observed()
        val light = Observed()
        setContent {
            DbideTheme(ThemeMode.DARK) { dark.record() }
            DbideTheme(ThemeMode.LIGHT) { light.record() }
        }
        waitForIdle()

        assertEquals(DarkScheme.surface, dark.surface)
        assertEquals(LightScheme.surface, light.surface)
        // Both colour systems move together. The failure this guards is the one that
        // looks fine until the editor is opened: Material swapped, the hand-drawn
        // palette left behind, and dark syntax on a white page.
        assertEquals(DarkExtras.syntax.keyword, dark.editorKeyword)
        assertEquals(LightExtras.syntax.keyword, light.editorKeyword)
    }

    /**
     * `SYSTEM` is a pointer at one of the other two, never a third palette of its
     * own. Which one it points at is the desktop's business and not this test's, so
     * the assertion is that it agrees with what Compose reports.
     */
    @Test
    fun `system follows the desktop and invents nothing`() = runDesktopComposeUiTest {
        val system = Observed()
        var desktopIsDark = false
        setContent {
            desktopIsDark = isSystemInDarkTheme()
            DbideTheme(ThemeMode.SYSTEM) { system.record() }
        }
        waitForIdle()

        val expected = if (desktopIsDark) DarkScheme else LightScheme
        assertEquals(expected.surface, system.surface)
        assertEquals(expected.onSurface, system.onSurface)
    }

    /**
     * The choice, made where a user makes it: through the workspace's own Settings
     * window, on the view model that persists it, with the window watching.
     */
    @Test
    fun `choosing a theme in settings repaints the window behind it`() = runDesktopComposeUiTest {
        val service = FakeConnectionService(VaultState.UNLOCKED)
        service.seed(name = "Live", engine = Engine.POSTGRES, status = RuntimeStatus.OPEN)
        val store = RecordingStore()
        val shell = Observed()
        lateinit var theme: ThemeViewModel

        setContent {
            val scope = rememberCoroutineScope()
            theme = remember { ThemeViewModel(store, scope, ThemeMode.DARK) }
            DbideTheme(theme.mode) {
                shell.record()
                WorkspaceScreen(
                    remember { ConnectionsViewModel(service, scope) },
                    remember { SchemaTreeViewModel(service, scope) },
                    remember { EditorTabs(service, scope) { null } },
                    remember { RedisWorkspace(service, scope) },
                    remember { HistoryViewModel(service, scope) },
                    theme,
                    onLock = {},
                    shortcuts = remember { Shortcuts() },
                )
            }
        }
        waitForIdle()
        assertEquals(DarkScheme.surface, shell.surface)

        onNodeWithContentDescription("open-settings").performClick()
        waitForIdle()
        onNodeWithContentDescription("settings-dialog").assertIsDisplayed()

        onNodeWithContentDescription("theme-choice-light").performClick()
        waitForIdle()

        assertEquals(ThemeMode.LIGHT, theme.mode)
        assertEquals(LightScheme.surface, shell.surface)
        assertNotEquals(DarkScheme.surface, shell.surface)

        // The dialog is still open and still on the light palette: the point of
        // applying on the click rather than on a Save is that the window you are
        // choosing in is one of the windows that changes.
        onNodeWithContentDescription("settings-dialog").assertIsDisplayed()
        onNodeWithContentDescription("theme-choice-dark").assertIsDisplayed()

        waitUntil { store.written == "light" }
        assertTrue(store.written == "light", "the choice was not persisted")
    }

    /**
     * The launch frame, at the pixel.
     *
     * Every other test here reads what a composable was *told* the colours are. This
     * one reads what was actually put on the screen, because the bug it guards is
     * exactly the gap between the two: a window whose content draws nothing was
     * rendering the platform's cleared surface — white — under a theme that had
     * correctly worked out it was dark.
     */
    @TestFactory
    fun `a frame with nothing in it is still the theme's colour`(): List<DynamicTest> =
        listOf(ThemeMode.DARK to DarkScheme, ThemeMode.LIGHT to LightScheme).map { (mode, scheme) ->
            DynamicTest.dynamicTest("${mode.wire}: an empty frame is painted") {
                runDesktopComposeUiTest(width = 64, height = 64) {
                    // Deliberately empty: this is the composition the application is
                    // in while the configuration database is being opened.
                    setContent { DbideTheme(mode) { AppSurface { } } }
                    waitForIdle()

                    val painted = onNodeWithContentDescription("app-surface")
                        .captureToImage()
                        .toPixelMap()

                    assertEquals(
                        scheme.background,
                        painted[painted.width / 2, painted.height / 2],
                        "the empty frame was not painted in the theme",
                    )
                }
            }
        }

    private class RecordingStore : MetadataStore {
        @Volatile
        var written: String? = null
            private set

        override suspend fun getMetadata(key: String): ByteArray? = null

        override suspend fun putMetadata(key: String, value: ByteArray) {
            if (key == ThemeViewModel.KEY) written = value.decodeToString()
        }
    }
}
