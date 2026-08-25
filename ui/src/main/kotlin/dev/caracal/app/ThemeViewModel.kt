package dev.caracal.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.caracal.app.ui.ThemeMode
import dev.caracal.core.vault.MetadataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Which theme the application is in, and remembering it between launches.
 *
 * The preference lives in `app_metadata` beside the vault's own keys rather than in
 * a file of its own, because it is the same question — something small the
 * application knows before anyone has unlocked anything. It is deliberately *not*
 * encrypted: which theme someone likes is not a secret, and putting it behind the
 * master password would mean the first screen of every launch is the one screen
 * that cannot honour the setting.
 *
 * A store that refuses to answer is not an error the user needs to hear about. The
 * theme falls back to [ThemeMode.DEFAULT] and the application opens.
 *
 * [initial] is what the startup read already found, so the first frame that has any
 * content in it is already in the user's theme rather than in the default for one
 * frame and theirs on the next. [load] remains for the case that read did not
 * happen — a store handed over later, or a test.
 */
class ThemeViewModel(
    private val store: MetadataStore?,
    private val scope: CoroutineScope,
    initial: ThemeMode = ThemeMode.DEFAULT,
) {
    var mode: ThemeMode by mutableStateOf(initial)
        private set

    fun load() {
        val store = store ?: return
        scope.launch {
            runCatching { store.getMetadata(KEY) }
                .getOrNull()
                ?.let { mode = ThemeMode.of(it.decodeToString()) }
        }
    }

    /**
     * Applied immediately, saved afterwards.
     *
     * The frame does not wait for a disk write, and a disk write that fails costs
     * the user the setting on the next launch rather than the click they just made.
     */
    fun select(next: ThemeMode) {
        mode = next
        val store = store ?: return
        scope.launch { runCatching { store.putMetadata(KEY, next.wire.encodeToByteArray()) } }
    }

    /** What the toggle in the shell does: System, then Light, then Dark, then round. */
    fun cycle() {
        val entries = ThemeMode.entries
        select(entries[(entries.indexOf(mode) + 1) % entries.size])
    }

    companion object {
        internal const val KEY = "ui_theme"

        /**
         * The preference, read on the thread that is already opening the store.
         *
         * It costs one row on a database that has just been opened anyway, and it
         * buys the alternative to reading it a frame later: a window that opens in
         * one theme and changes to another while someone is looking at it.
         */
        suspend fun read(store: MetadataStore): ThemeMode =
            runCatching { ThemeMode.of(store.getMetadata(KEY)?.decodeToString()) }
                .getOrDefault(ThemeMode.DEFAULT)
    }
}
