package dev.dbide.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dbide.app.ui.ThemeMode
import dev.dbide.core.vault.MetadataStore
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
 */
class ThemeViewModel(
    private val store: MetadataStore?,
    private val scope: CoroutineScope,
) {
    var mode: ThemeMode by mutableStateOf(ThemeMode.DEFAULT)
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

    private companion object {
        const val KEY = "ui_theme"
    }
}
