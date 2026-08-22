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
import dev.caracal.app.RedisValueViewModel
import dev.caracal.core.connections.ConnectionId
import dev.caracal.engine.api.FieldEntry
import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.MemoryEstimate
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.TextValue
import dev.caracal.engine.api.ScoredMember
import dev.caracal.engine.api.Ttl
import dev.caracal.engine.api.ValuePage
import dev.caracal.core.vault.VaultState
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * The value pane through the real composables.
 *
 * The two assertions worth the mounting cost: that a copy takes what Redis holds
 * rather than what the pane drew, and that a key which is gone reads as a key which is
 * gone rather than as a broken workspace.
 */
@OptIn(ExperimentalTestApi::class)
class RedisValueUiTest {

    private val id = ConnectionId("id-1")
    private val key = KeyRef("user:42".toByteArray())

    private fun text(value: String) = TextValue.Utf8(value, byteCount = value.length)

    private fun metadata(type: KeyType?, unsupported: String? = null) = KeyMetadata(
        key = key,
        type = type,
        ttl = if (type == null) Ttl.Gone else Ttl.ExpiresIn(3580),
        memory = if (type == null) MemoryEstimate.Absent else MemoryEstimate.Bytes(912),
        unsupportedType = unsupported,
    )

    private fun ComposeUiTest.pane(
        service: FakeConnectionService,
        copied: MutableList<String> = mutableListOf(),
    ): RedisValueViewModel {
        lateinit var model: RedisValueViewModel
        setContent {
            val scope = rememberCoroutineScope()
            model = remember { RedisValueViewModel(service, scope) }
            LaunchedEffect(Unit) {
                model.show(id)
                model.open(key)
            }
            CaracalTheme { RedisValueViewer(model, onCopy = { copied += it }) }
        }
        waitForIdle()
        return model
    }

    private fun service(page: ValuePage, type: KeyType) =
        FakeConnectionService(VaultState.UNLOCKED).apply {
            keyMetadata[key] = metadata(type)
            valuePage = page
        }

    @Test
    fun `a string shows its text, and its JSON only when it is JSON`() =
        runDesktopComposeUiTest(width = 700, height = 700) {
            val document = """{"id":42}"""
            val copied = mutableListOf<String>()
            val service = service(
                ValuePage.Text(
                    key = key,
                    content = TextValue.Utf8(document, byteCount = document.length),
                    offset = 0,
                    nextOffset = null,
                    length = document.length,
                    complete = true,
                ),
                KeyType.STRING,
            )
            pane(service, copied)

            onNodeWithTag("value-key-name").assertIsDisplayed()
            onNodeWithTag("value-text").assertIsDisplayed()

            onNodeWithTag("value-json").performClick()
            waitForIdle()
            onNodeWithTag("value-json-text").assertIsDisplayed()

            onNodeWithTag("value-copy").performClick()
            waitForIdle()

            // §3.7: a copy is of the original bytes, not of what this pane reformatted.
            assertEquals(listOf(document), copied)
        }

    @Test
    fun `a string that is not text is shown as bytes and says so`() =
        runDesktopComposeUiTest(width = 700, height = 700) {
            val service = service(
                ValuePage.Text(
                    key = key,
                    content = TextValue.Binary("ff00ab", byteCount = 3),
                    offset = 0,
                    nextOffset = null,
                    length = 3,
                    complete = true,
                ),
                KeyType.STRING,
            )
            pane(service)

            onNodeWithTag("value-binary").assertIsDisplayed()
            // No JSON tab at all: there is no complete UTF-8 text to parse.
            onNodeWithTag("value-json").assertDoesNotExist()
        }

    @Test
    fun `a hash is a table, and Show more reads the next page`() =
        runDesktopComposeUiTest(width = 700, height = 700) {
            val service = service(
                ValuePage.Fields(
                    key = key,
                    entries = listOf(FieldEntry(text("email"), text("a@example.com"))),
                    cursor = ScanCursor.of("17"),
                    complete = false,
                ),
                KeyType.HASH,
            )
            pane(service)

            onNodeWithTag("value-hash").assertIsDisplayed()
            onNodeWithText("email").assertIsDisplayed()

            onNodeWithTag("value-more").performClick()
            waitForIdle()

            assertEquals(2, service.calls.count { it.startsWith("readValue") })
        }

    @Test
    fun `an empty collection is not a missing key`() =
        runDesktopComposeUiTest(width = 700, height = 700) {
            val service = service(
                ValuePage.Members(key, emptyList(), ScanCursor.START, complete = true),
                KeyType.SET,
            )
            pane(service)

            onNodeWithTag("value-empty-collection").assertIsDisplayed()
            onNodeWithTag("value-missing").assertDoesNotExist()
        }

    @Test
    fun `a key that expired says so without taking the pane with it`() =
        runDesktopComposeUiTest(width = 700, height = 700) {
            val service = FakeConnectionService(VaultState.UNLOCKED).apply {
                keyMetadata[key] = metadata(type = null)
            }
            pane(service)

            onNodeWithTag("value-missing").assertIsDisplayed()
            // Not an error banner: nothing failed.
            onNodeWithTag("value-retry").assertIsDisplayed()
        }

    @Test
    fun `a sorted set says that ranked paging can move a member`() =
        runDesktopComposeUiTest(width = 700, height = 700) {
            val service = service(
                ValuePage.Scored(
                    key = key,
                    members = listOf(ScoredMember(text("alice"), "1.5")),
                    offset = 0,
                    nextOffset = null,
                    total = 1,
                    complete = true,
                ),
                KeyType.ZSET,
            )
            pane(service)

            onNodeWithTag("value-zset").assertIsDisplayed()
            onNodeWithText("1.5").assertIsDisplayed()
            onNodeWithTag("value-rank-note").assertIsDisplayed()
        }
}
