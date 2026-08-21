package dev.caracal.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.BuildInfo
import dev.caracal.app.ThemeViewModel
import dev.caracal.core.appdata.AppPaths
import dev.caracal.core.vault.MetadataStore
import org.junit.jupiter.api.Test

/**
 * §5.4 from the outside: the two questions a bug report opens with, answered in the
 * window rather than only on a command line a packaged application is rarely started
 * from.
 */
@OptIn(ExperimentalTestApi::class)
class AboutUiTest {

    private class NoStore : MetadataStore {
        override suspend fun getMetadata(key: String): ByteArray? = null
        override suspend fun putMetadata(key: String, value: ByteArray) = Unit
    }

    @Test
    fun `about names this build and the directory its data is in`() = runDesktopComposeUiTest {
        setContent {
            val scope = rememberCoroutineScope()
            val theme = remember { ThemeViewModel(NoStore(), scope, ThemeMode.DARK) }
            CaracalTheme(theme.mode) {
                SettingsDialog(theme, onDismiss = {})
            }
        }
        waitForIdle()

        onNodeWithTag("settings-about").performClick()
        waitForIdle()

        // The version as the application itself reports it — not a literal, which
        // would only assert that two copies of the same mistake agree.
        onNodeWithTag("about-version").assertTextContains(BuildInfo.current.version, substring = true)
        onNodeWithTag("about-version").assertTextContains(BuildInfo.current.commit, substring = true)

        // A local-first application has to be able to point at the file. Recomputed
        // here rather than hardcoded, because it answers to CARACAL_DATA_DIR and a
        // development run is pointed somewhere else on purpose.
        onNodeWithTag("about-data-directory")
            .assertTextContains(AppPaths.configDatabase().parent.toString())
    }
}
