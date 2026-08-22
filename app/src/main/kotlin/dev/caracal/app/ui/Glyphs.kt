package dev.caracal.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import dev.caracal.app.RowKind
import dev.caracal.engine.api.EngineId
import dev.caracal.core.connections.Environment
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.ObjectKind

/**
 * The application's glyph vocabulary.
 *
 * One emoji per kind of thing, chosen once and used everywhere that kind appears —
 * a table is 📋 in the tree, in a menu, and in a message, because a glyph that means
 * two things in two panes is a glyph the eye has to stop and read rather than one it
 * can skip along.
 *
 * Two rules keep them from costing anything. Every glyph sits *beside* a word rather
 * than replacing one, so a machine with no colour-emoji font loses decoration and
 * never loses meaning; and every glyph is drawn through [Glyph] below, which strips
 * itself from the accessibility tree — a screen reader announcing "clipboard users"
 * for a table called `users` is worse than one announcing `users`.
 *
 * The codepoints are deliberately ones with emoji presentation by default, so none
 * of them depends on a variation selector surviving the font stack.
 */
object Glyphs {
    // Panes and tabs.
    const val CONNECTIONS = "🔌"
    const val DATABASE = "🐘"

    /**
     * An engine with no glyph of its own.
     *
     * Storage rather than an animal, and chosen from the codepoints that carry emoji
     * presentation without a variation selector — the filing-cabinet glyph does not,
     * and renders as a black-and-white box on the fonts that matter.
     */
    const val ENGINE = "💾"
    const val KEYS = "🔑"
    const val QUERY = "📝"
    const val VALUE = "🔎"
    const val CONSOLE = "💻"
    const val SERVER = "📊"
    const val HISTORY = "🕘"
    const val SETTINGS = "🧰"
    const val APPEARANCE = "🎨"
    const val ABOUT = "💡"
    const val SHORTCUTS = "🔣"

    // Schema objects.
    const val SCHEMA = "📁"
    const val SCHEMA_OPEN = "📂"
    const val TABLE = "📋"
    const val VIEW = "👓"
    const val MATERIALIZED_VIEW = "🧊"
    const val FUNCTION = "🔧"
    const val COLUMN = "🔹"
    const val PRIMARY_KEY = "🔑"

    // Safety.
    const val PROD = "🚨"
    const val STAGING = "🚧"
    const val DEV = "🧪"
    const val READ_ONLY = "🔒"
    const val WARNING = "❗"

    /**
     * The engines, for the places a drawn [EngineLogo] is more than the row can hold.
     *
     * Keyed on the engine's name and with a fallback, for the same reason the logo is:
     * a glyph cannot be declared in `:engine-api`, and an engine this build has no
     * glyph for should get the generic database mark rather than an empty space where
     * a section heading's icon belongs.
     */
    fun of(engine: EngineId): String = when (engine.value) {
        "postgres" -> "🐘"
        "redis" -> "🧱"
        else -> ENGINE
    }

    fun of(environment: Environment): String = when (environment) {
        Environment.PROD -> PROD
        Environment.STAGING -> STAGING
        Environment.DEV -> DEV
    }

    /** What a tree row is. A folder opens, so it has two. */
    fun of(kind: RowKind, expanded: Boolean = false): String = when (kind) {
        RowKind.SCHEMA -> if (expanded) SCHEMA_OPEN else SCHEMA
        RowKind.FOLDER -> if (expanded) SCHEMA_OPEN else SCHEMA
        RowKind.TABLE -> TABLE
        RowKind.VIEW -> VIEW
        RowKind.MATERIALIZED_VIEW -> MATERIALIZED_VIEW
        RowKind.FUNCTION -> FUNCTION
        RowKind.COLUMN -> COLUMN
    }

    fun of(kind: ObjectKind): String = when (kind) {
        ObjectKind.TABLE -> TABLE
        ObjectKind.VIEW -> VIEW
        ObjectKind.MATERIALIZED_VIEW -> MATERIALIZED_VIEW
        ObjectKind.FUNCTION -> FUNCTION
    }

    /**
     * What a Redis key holds.
     *
     * The shapes follow the data structure rather than the word: a list is a scroll
     * because it is ordered, a set is a target because it is not, and a sorted set
     * is a podium because its order is a score.
     */
    fun of(type: KeyType): String = when (type) {
        KeyType.STRING -> "🔤"
        KeyType.LIST -> "📜"
        KeyType.SET -> "🎯"
        KeyType.ZSET -> "🏆"
        KeyType.HASH -> "🧩"
        KeyType.STREAM -> "🌊"
    }
}

/**
 * One glyph, drawn as decoration.
 *
 * `clearAndSetSemantics {}` with nothing in it is the point: the glyph leaves the
 * accessibility tree entirely, and it also leaves the text-matching tree that the UI
 * tests search — so a row labelled `email` is still found by its name and not by a
 * name a glyph has been glued onto.
 *
 * Sized in `sp` and *not* scaled with the type ramp beside it. An emoji set at the
 * label's own size reads as a second word competing with the first; a shade under it
 * reads as a bullet, which is what this is.
 */
@Composable
fun Glyph(glyph: String, modifier: Modifier = Modifier, size: Int = 11) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = size.sp, lineHeight = (size + 3).sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clearAndSetSemantics {},
    )
}
