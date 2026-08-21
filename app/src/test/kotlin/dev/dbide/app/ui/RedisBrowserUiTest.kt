package dev.dbide.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.dbide.app.FakeConnectionService
import dev.dbide.app.RedisBrowserViewModel
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.redis.KeyMetadata
import dev.dbide.core.redis.KeyType
import dev.dbide.core.redis.MemoryEstimate
import dev.dbide.core.redis.RedisCursor
import dev.dbide.core.redis.RedisKey
import dev.dbide.core.redis.ScanPage
import dev.dbide.core.redis.ScanStop
import dev.dbide.core.redis.Ttl
import dev.dbide.core.vault.VaultState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The key browser, driven through the real composables.
 *
 * These are the assertions a correct view model behind a broken pane would still fail:
 * that a group is a group and opening it needs no server, that a key hands its *bytes*
 * to whatever opens it rather than the label that was drawn, and that a traversal which
 * stopped early says so instead of looking finished.
 */
@OptIn(ExperimentalTestApi::class)
class RedisBrowserUiTest {

    private val id = ConnectionId("id-1")

    private fun key(name: String, type: KeyType = KeyType.STRING) = KeyMetadata(
        key = RedisKey(name.toByteArray()),
        type = type,
        ttl = Ttl.ExpiresIn(90),
        memory = MemoryEstimate.Bytes(912),
    )

    private fun page(
        vararg keys: KeyMetadata,
        cursor: String = "0",
        stopped: ScanStop = ScanStop.COMPLETE,
    ) = ScanPage(RedisCursor.of(cursor), keys.toList(), iterations = 1, stopped = stopped)

    private fun ComposeUiTest.browser(
        service: FakeConnectionService,
        opened: MutableList<RedisKey> = mutableListOf(),
    ): RedisBrowserViewModel {
        lateinit var model: RedisBrowserViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { RedisBrowserViewModel(service, scope) }
            LaunchedEffect(Unit) { model.show(id) }
            DbideTheme { RedisKeyBrowser(model, onOpenKey = { opened += it }) }
        }
        waitForIdle()
        return model
    }

    @Test
    fun `keys are grouped by their own names, and opening a group asks nothing`() =
        runDesktopComposeUiTest(width = 420, height = 700) {
            val service = FakeConnectionService(VaultState.UNLOCKED).apply {
                scanPage = page(key("user:1:profile"), key("user:2:profile"), key("health"))
            }
            browser(service)
            val reads = service.calls.size

            onNodeWithContentDescription("redis-group-user").assertIsDisplayed()
            // A key with no delimiter in it is a leaf at the root rather than a group
            // of one.
            onNodeWithContentDescription("redis-key-health").assertIsDisplayed()
            onNodeWithContentDescription("redis-key-profile").assertDoesNotExist()

            onNodeWithContentDescription("redis-group-user").performClick()
            waitForIdle()

            onNodeWithContentDescription("redis-group-1").assertIsDisplayed()
            // The tree is a presentation of keys already scanned. It has nothing to call.
            assertEquals(reads, service.calls.size)
        }

    @Test
    fun `clicking a key hands over its bytes, not the label that was drawn`() =
        runDesktopComposeUiTest(width = 420, height = 700) {
            val service = FakeConnectionService(VaultState.UNLOCKED).apply {
                scanPage = page(key("user:42:profile"))
            }
            val opened = mutableListOf<RedisKey>()
            browser(service, opened)

            onNodeWithContentDescription("redis-group-user").performClick()
            waitForIdle()
            onNodeWithContentDescription("redis-group-42").performClick()
            waitForIdle()
            // The row is labelled `profile` — the last segment — and what it produces
            // is the whole key. §3.4's rule that a command is never reconstructed from
            // a display label, asserted at the one place a label could be mistaken for
            // one.
            onNodeWithContentDescription("redis-key-profile").performClick()
            waitForIdle()

            assertEquals(listOf("user:42:profile"), opened.map { it.text })
        }

    @Test
    fun `a row carries its type, its countdown, and its size`() =
        runDesktopComposeUiTest(width = 420, height = 700) {
            val service = FakeConnectionService(VaultState.UNLOCKED).apply {
                scanPage = page(key("health", KeyType.HASH))
            }
            browser(service)

            onNodeWithContentDescription("key-type-hash").assertIsDisplayed()
            onNodeWithContentDescription("key-ttl").assertIsDisplayed()
            onNodeWithContentDescription("key-memory").assertIsDisplayed()
        }

    @Test
    fun `a traversal that stopped on a budget offers to continue`() =
        runDesktopComposeUiTest(width = 420, height = 700) {
            val service = FakeConnectionService(VaultState.UNLOCKED).apply {
                scanPage = page(key("a"), cursor = "88", stopped = ScanStop.ITERATION_BUDGET)
            }
            browser(service)

            onNodeWithContentDescription("keys-load-more").assertIsDisplayed()
            onNodeWithContentDescription("keys-progress").assertIsDisplayed()
            // The warning is permanent rather than a one-time notice: it is how SCAN
            // works, and it applies to every page on screen.
            onNodeWithContentDescription("keys-snapshot-warning").assertIsDisplayed()

            onNodeWithContentDescription("keys-load-more").performClick()
            waitForIdle()

            assertTrue(service.calls.count { it.startsWith("redisScan") } >= 2)
            assertTrue(service.calls.last { it.startsWith("redisScan") }.contains("88"))
        }

    @Test
    fun `an empty scan that finished and one that has not are different sentences`() =
        runDesktopComposeUiTest(width = 420, height = 700) {
            val service = FakeConnectionService(VaultState.UNLOCKED).apply {
                scanPage = page()
            }
            browser(service)

            onNodeWithContentDescription("keys-empty-complete").assertIsDisplayed()

            // The other case: the cursor is still advancing and nothing has matched
            // yet, which is a fact about the budget rather than about the keyspace.
            service.scanPage = page(cursor = "12", stopped = ScanStop.PAGE_FULL)
            onNodeWithContentDescription("keys-refresh").performClick()
            waitForIdle()

            onNodeWithContentDescription("keys-empty-partial").assertIsDisplayed()
        }

    @Test
    fun `an empty filtered scan names the filters and offers to clear them`() =
        runDesktopComposeUiTest(width = 420, height = 700) {
            val service = FakeConnectionService(VaultState.UNLOCKED).apply {
                scanPage = page(key("session:a"))
            }
            val model = browser(service)

            // A pattern that matches nothing, so the empty state has to explain
            // itself in terms of the filter rather than the keyspace.
            service.scanPage = page()
            onNodeWithContentDescription("keys-pattern").performTextInput("nope:*")
            onNodeWithContentDescription("keys-search").performClick()
            waitForIdle()

            onNodeWithContentDescription("keys-empty-complete").assertIsDisplayed()
            onNodeWithContentDescription("keys-clear-filters").assertIsDisplayed()

            service.scanPage = page(key("session:a"))
            onNodeWithContentDescription("keys-clear-filters").performClick()
            waitForIdle()

            // Both the box and the traversal, so the browser is back where it started.
            assertEquals("", model.pattern)
            assertEquals("redisScan(0, null, null)", service.calls.last { it.startsWith("redisScan") })
            onNodeWithContentDescription("keys-clear-filters").assertDoesNotExist()
        }

    @Test
    fun `an empty unfiltered scan offers nothing to clear`() =
        runDesktopComposeUiTest(width = 420, height = 700) {
            val service = FakeConnectionService(VaultState.UNLOCKED).apply {
                scanPage = page()
            }
            browser(service)

            // An empty database is not a filter problem, and a button that would
            // change nothing is a button that teaches people to distrust them.
            onNodeWithContentDescription("keys-empty-complete").assertIsDisplayed()
            onNodeWithContentDescription("keys-clear-filters").assertDoesNotExist()
        }

    @Test
    fun `a pattern is sent when it is searched`() =
        runDesktopComposeUiTest(width = 420, height = 700) {
            val service = FakeConnectionService(VaultState.UNLOCKED).apply {
                scanPage = page(key("session:a"))
            }
            browser(service)

            onNodeWithContentDescription("keys-pattern").performTextInput("session:*")
            waitForIdle()
            onNodeWithContentDescription("keys-search").performClick()
            waitForIdle()

            assertEquals(
                "redisScan(0, session:*, null)",
                service.calls.last { it.startsWith("redisScan") },
            )
        }
}
