package dev.caracal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.caracal.app.BuildInfo
import dev.caracal.app.ThemeViewModel
import dev.caracal.core.appdata.AppPaths
import dev.caracal.core.connections.Engine
import dev.caracal.core.engines.capabilities
import dev.caracal.core.engines.displayName
import dev.caracal.engine.api.EngineFamily

/** Which section of the settings window is showing. */
private enum class SettingsSection(val label: String, val glyph: String, val detail: String) {
    // Not "Theme and density": the density is one deliberate desk-distance answer
    // tuned in Space, Sizes, and DenseTypography, and it is not a preference. A rail
    // entry naming a setting that is not in the panel is a rail entry that lies.
    APPEARANCE("Appearance", Glyphs.APPEARANCE, "How the application looks"),
    ENGINES("Engines", Glyphs.DATABASE, "What this build can talk to"),
    ABOUT("About", Glyphs.ABOUT, "Which build this is, and where your data lives"),
}

/**
 * The application's settings, as a window.
 *
 * The theme used to be a button in the shell that cycled through three values, which
 * is a control that can only be operated by pressing it and seeing what happens —
 * fine for two states, guesswork for three. Here the three are laid out with what
 * each one means, the current one is marked, and choosing one is a click on the thing
 * you want rather than a click on the thing you have.
 *
 * It is a rail-and-panel window rather than a list for the same reason the connection
 * dialog is: this is where the *next* preference goes, and a settings window that has
 * to be reorganised to hold a second section was the wrong shape to begin with.
 */
@Composable
fun SettingsDialog(theme: ThemeViewModel, onDismiss: () -> Unit) {
    var section: SettingsSection by remember { mutableStateOf(SettingsSection.APPEARANCE) }

    AppDialog(
        title = "Settings",
        subtitle = "Preferences are stored unencrypted beside the vault, so the first screen can honour them.",
        tag = "settings-dialog",
        icon = { AppMark(size = 26.dp) },
        onDismiss = onDismiss,
        maxWidth = 760.dp,
        maxHeight = 560.dp,
        rail = {
            RailHeading("Application")
            SettingsSection.entries.forEach { entry ->
                RailItem(
                    label = entry.label,
                    detail = entry.detail,
                    tag = "settings-${entry.name.lowercase()}",
                    selected = section == entry,
                    onClick = { section = entry },
                    leading = { Glyph(entry.glyph, size = 14) },
                )
            }
        },
        footer = {
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                shape = MaterialTheme.shapes.small,
                onClick = onDismiss,
                modifier = Modifier.testTag("settings-done"),
            ) {
                Text("Done")
            }
        },
    ) {
        when (section) {
            SettingsSection.APPEARANCE -> Appearance(theme)
            SettingsSection.ENGINES -> Engines()
            SettingsSection.ABOUT -> About()
        }
    }
}

/**
 * The theme, as three choices with their reasons.
 *
 * Applied on the click and not on a Save, which is what makes the choice legible: the
 * window behind the dialog is already in the theme being chosen, and the dialog is
 * too.
 */
@Composable
private fun Appearance(theme: ThemeViewModel) {
    DialogSection(
        title = "Theme",
        glyph = Glyphs.APPEARANCE,
        detail = "Applied immediately, and remembered for the next launch.",
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.md),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            ThemeMode.entries.forEach { mode ->
                RailItem(
                    label = mode.label,
                    detail = mode.blurb,
                    tag = "theme-choice-${mode.wire}",
                    selected = theme.mode == mode,
                    onClick = { theme.select(mode) },
                    // Three answers to one question, inside a `selectableGroup`.
                    role = Role.RadioButton,
                    leading = { ThemeSwatch(mode) },
                )
            }
        }
    }
}

/** A two-tone chip standing for what a theme looks like, so the label is not alone. */
@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    val dark = Color(0xFF15171C)
    val light = Color(0xFFF6F7F9)
    Row(
        modifier = Modifier
            .size(width = 26.dp, height = 18.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .border(Sizes.hairline, Caracal.colors.hairline, MaterialTheme.shapes.extraSmall),
    ) {
        val halves = when (mode) {
            ThemeMode.LIGHT -> listOf(light, light)
            ThemeMode.DARK -> listOf(dark, dark)
            ThemeMode.SYSTEM -> listOf(light, dark)
        }
        halves.forEach { half ->
            Box(modifier = Modifier.weight(1f).height(18.dp).background(half))
        }
    }
}

private val ThemeMode.blurb: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "Follow whatever the desktop is set to"
        ThemeMode.LIGHT -> "For a bright room and a projector"
        ThemeMode.DARK -> "The default, for a window that sits beside a terminal"
    }

/** What this build can connect to, with the marks used everywhere else. */
@Composable
private fun Engines() {
    DialogSection(
        title = "Engines",
        glyph = Glyphs.DATABASE,
        detail = "Both are read through their own driver; neither is proxied through a server of ours.",
    ) {
        Engine.entries.forEach { engine ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EngineTile(engine, size = 38.dp)
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        // The engine's own name for itself. This window used to spell
                        // both out, which meant a third engine appeared here as a
                        // wire name until someone noticed.
                        text = engine.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = engine.summary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                EngineBadge(engine)
            }
        }
    }
}

/**
 * What this engine gives you, from what it says it can do.
 *
 * The tools listed are the panes [WorkspaceScreen] opens for that family, so the two
 * cannot drift: an engine that gets the SQL workspace is described as having a SQL
 * workspace. The port comes off the capability rather than being written out again,
 * because a number typed twice is a number that is wrong once.
 */
private fun Engine.summary(): String {
    val tools = when (capabilities.family) {
        EngineFamily.SQL -> "Schema browser, SQL editor, CSV export."
        EngineFamily.KEY_VALUE -> "Keyspace browser, value viewer, command console."
        EngineFamily.DOCUMENT -> "Connection management."
    }
    return capabilities.defaultPort?.let { "$tools Default port $it." } ?: tools
}

/**
 * Where the data is.
 *
 * The one question this section exists to answer is the one a local-first tool has to
 * answer out loud: nothing here has been sent anywhere, and here is the directory
 * that proves it.
 */
@Composable
private fun About() {
    DialogSection(title = "Caracal", glyph = Glyphs.ABOUT) {
        Text(
            "A local desktop client for PostgreSQL and Redis. Connections, saved passwords, " +
                "and query history live in one file on this machine and are never sent anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.md),
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(Space.lg),
        ) {
            val build = BuildInfo.current
            AboutRow(
                "Version",
                // Version, commit, and date on one line, in the shape a bug report
                // wants pasted into it — which is why it is selectable text rather
                // than three decorated rows.
                listOfNotNull(
                    build.version,
                    "commit ${build.commit}",
                    build.builtOn()?.let { "built $it" },
                ).joinToString(" · "),
                tag = "about-version",
            )
            AboutRow("Data", dataDirectory, tag = "about-data-directory")
            AboutRow("Passwords", "Sealed with your master password. Never shown, never logged.")
            AboutRow("Telemetry", "None.")
            AboutRow("Write safety", "Statements that modify data are confirmed; production is typed out.")
        }
    }
}

/**
 * The directory holding the configuration database.
 *
 * Resolved once per composition of the panel and never cached across launches: it
 * answers to `CARACAL_DATA_DIR`, and a development run pointed at a scratch
 * directory should say the scratch directory.
 */
private val dataDirectory: String
    get() = AppPaths.configDatabase().parent.toString()

@Composable
private fun AboutRow(label: String, value: String, tag: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = if (tag == null) Modifier else Modifier.testTag(tag),
            )
        }
    }
}
