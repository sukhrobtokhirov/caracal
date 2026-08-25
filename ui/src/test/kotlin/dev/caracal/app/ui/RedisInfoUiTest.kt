package dev.caracal.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.RedisInfoViewModel
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.vault.VaultState
import dev.caracal.engine.api.DatabaseKeyspace
import dev.caracal.engine.api.ServerInfo
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

/**
 * The `INFO` dashboard, and the fields it does not have.
 *
 * Every assertion here is about absence, because that is what §3.8 is about. A card
 * per known field would fill a restricted production screen with zeroes, and a zero is
 * a claim: "no cache hits" reads as a broken cache on exactly the screen someone opens
 * when they suspect the cache is broken.
 */
@OptIn(ExperimentalTestApi::class)
class RedisInfoUiTest {

    private val id = ConnectionId("id-1")

    private fun ComposeUiTest.dashboard(service: FakeConnectionService): RedisInfoViewModel {
        lateinit var model: RedisInfoViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { RedisInfoViewModel(service, scope) }
            LaunchedEffect(Unit) { model.show(id) }
            CaracalTheme { RedisInfoDashboard(model) }
        }
        waitForIdle()
        return model
    }

    private fun service(info: ServerInfo) = FakeConnectionService(VaultState.UNLOCKED).apply {
        serverInfo = info
    }

    @Test
    fun `a full summary draws the cards it has answers for`() =
        runDesktopComposeUiTest(width = 900, height = 800) {
            val service = service(
                ServerInfo(
                    version = "7.2.4",
                    mode = "standalone",
                    role = "master",
                    uptime = 90_000.seconds,
                    connectedClients = 12,
                    usedMemoryBytes = 4_194_304,
                    maxMemoryBytes = 67_108_864,
                    maxMemoryPolicy = "allkeys-lru",
                    keyspaceHits = 900,
                    keyspaceMisses = 100,
                    totalCommands = 1_234_567,
                    opsPerSecond = 42,
                    databases = listOf(DatabaseKeyspace(0, keys = 1200, expires = 300)),
                ),
            )
            dashboard(service)

            onNodeWithTag("info-card-version").assertIsDisplayed()
            onNodeWithTag("info-card-hit-rate").assertIsDisplayed()
            onNodeWithText("90.0%").assertIsDisplayed()
            onNodeWithTag("info-db-0").assertIsDisplayed()
            onNodeWithText("1,200 keys").assertIsDisplayed()
            onNodeWithTag("info-restricted").assertDoesNotExist()
        }

    @Test
    fun `a partial summary draws no card for a field the server did not report`() =
        runDesktopComposeUiTest(width = 900, height = 800) {
            // A user without the stats section: no hits, no misses, no commands.
            val service = service(ServerInfo(version = "7.2.4", connectedClients = 3))
            dashboard(service)

            onNodeWithTag("info-card-version").assertIsDisplayed()
            onNodeWithTag("info-card-clients").assertIsDisplayed()
            // Not "0%", which would be a statement about the cache rather than about
            // this user's permissions.
            onNodeWithTag("info-card-hit-rate").assertDoesNotExist()
            onNodeWithTag("info-card-commands").assertDoesNotExist()
        }

    @Test
    fun `a server with no lookups yet has no hit rate rather than a rate of nought`() =
        runDesktopComposeUiTest(width = 900, height = 800) {
            val service = service(
                ServerInfo(version = "7.2.4", keyspaceHits = 0, keyspaceMisses = 0),
            )
            dashboard(service)

            onNodeWithTag("info-card-hit-rate").assertDoesNotExist()
        }

    @Test
    fun `a refused INFO is a banner, not a failure`() =
        runDesktopComposeUiTest(width = 900, height = 800) {
            val service = service(ServerInfo(restricted = true))
            dashboard(service)

            onNodeWithTag("info-restricted").assertIsDisplayed()
            onNodeWithTag("info-empty").assertIsDisplayed()
            // The connection is fine. Only this one command was refused.
            onNodeWithTag("failure-query_failed").assertDoesNotExist()
        }

    @Test
    fun `the dashboard reads once and then only when asked`() =
        runDesktopComposeUiTest(width = 900, height = 800) {
            val service = service(ServerInfo(version = "7.2.4"))
            dashboard(service)

            assertEquals(1, service.calls.count { it == "serverMetrics" })

            onNodeWithTag("info-refresh").performClick()
            waitForIdle()

            // Twice, because it was asked twice. §3.8 has no polling in it.
            assertEquals(2, service.calls.count { it == "serverMetrics" })
        }
}
