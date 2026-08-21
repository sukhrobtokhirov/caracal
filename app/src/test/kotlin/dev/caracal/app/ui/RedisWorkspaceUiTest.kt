package dev.caracal.app.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.caracal.app.ConnectionsViewModel
import dev.caracal.app.EditorTabs
import dev.caracal.app.HistoryViewModel
import dev.caracal.app.FakeConnectionService
import dev.caracal.app.RedisWorkspace
import dev.caracal.app.SchemaTreeViewModel
import dev.caracal.app.ThemeViewModel
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.redis.KeyMetadata
import dev.caracal.core.redis.KeyType
import dev.caracal.core.redis.MemoryEstimate
import dev.caracal.core.redis.RedisCursor
import dev.caracal.core.redis.RedisKey
import dev.caracal.core.redis.RedisText
import dev.caracal.core.redis.ScanPage
import dev.caracal.core.redis.ScanStop
import dev.caracal.core.redis.Ttl
import dev.caracal.core.redis.ValuePage
import dev.caracal.core.vault.VaultState
import org.junit.jupiter.api.Test

/**
 * The whole window with a Redis connection open.
 *
 * What is asserted is the shape of the workspace rather than any one pane: that an
 * engine gets the panes it has and none that it does not, and that clicking a key in
 * the browser puts its value where the user is looking. A tab strip offering a SQL
 * editor for a cache is a tab that can only ever say "not for this engine".
 */
@OptIn(ExperimentalTestApi::class)
class RedisWorkspaceUiTest {

    private val key = RedisKey("user:42".toByteArray())

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        scanPage = ScanPage(
            cursor = RedisCursor.START,
            keys = listOf(
                KeyMetadata(
                    key = key,
                    type = KeyType.STRING,
                    ttl = Ttl.ExpiresIn(60),
                    memory = MemoryEstimate.Bytes(64),
                ),
            ),
            iterations = 1,
            stopped = ScanStop.COMPLETE,
        )
        keyMetadata[key] = scanPage.keys.first()
        valuePage = ValuePage.Text(
            key = key,
            content = RedisText.Utf8("alice", byteCount = 5),
            offset = 0,
            nextOffset = null,
            length = 5,
            complete = true,
        )
    }

    private fun ComposeUiTest.workspace(service: FakeConnectionService) {
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
    }

    @Test
    fun `an open Redis connection gets the key browser and the Redis tabs`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = service()
            service.seed(name = "Cache", engine = Engine.REDIS, status = RuntimeStatus.OPEN)
            workspace(service)

            onNodeWithTag("connection-Cache").performClick()
            waitForIdle()

            onNodeWithTag("redis-browser").assertIsDisplayed()
            onNodeWithTag("workspace-tab-console").assertIsDisplayed()
            onNodeWithTag("workspace-tab-server").assertIsDisplayed()
            // Neither pane exists for this engine, and neither is offered.
            onNodeWithTag("workspace-tab-query").assertDoesNotExist()
            onNodeWithTag("schema-tree").assertDoesNotExist()
        }

    @Test
    fun `clicking a key shows its value`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = service()
            service.seed(name = "Cache", engine = Engine.REDIS, status = RuntimeStatus.OPEN)
            workspace(service)
            onNodeWithTag("connection-Cache").performClick()
            waitForIdle()

            onNodeWithTag("redis-group-user").performClick()
            waitForIdle()
            onNodeWithTag("redis-key-42").performClick()
            waitForIdle()

            onNodeWithTag("value-key-name").assertIsDisplayed()
            onNodeWithTag("value-text").assertIsDisplayed()
        }

    @Test
    fun `the console and the server summary are one click away`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = service()
            service.seed(name = "Cache", engine = Engine.REDIS, status = RuntimeStatus.OPEN)
            workspace(service)
            onNodeWithTag("connection-Cache").performClick()
            waitForIdle()

            onNodeWithTag("workspace-tab-console").performClick()
            waitForIdle()
            onNodeWithTag("redis-console").assertIsDisplayed()

            onNodeWithTag("workspace-tab-server").performClick()
            waitForIdle()
            onNodeWithTag("redis-info").assertIsDisplayed()
        }

    @Test
    fun `a closed Redis connection is not browsed`() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            val service = service()
            service.seed(name = "Cache", engine = Engine.REDIS, status = RuntimeStatus.CLOSED)
            workspace(service)

            onNodeWithTag("connection-Cache").performClick()
            waitForIdle()

            // A closed connection is not reopened to fill a panel: the user closed it.
            onNodeWithTag("redis-browser").assertDoesNotExist()
            assert(service.calls.none { it.startsWith("redisScan") })
        }
}
