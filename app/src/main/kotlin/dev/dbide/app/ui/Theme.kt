package dev.dbide.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Which theme the user asked for.
 *
 * `SYSTEM` is offered but is not the default. A database client is a window that
 * sits open beside a terminal for eight hours, and the terminal is dark; opening
 * bright white because the OS has never been told otherwise is a worse first frame
 * than opening dark and being told to lighten up.
 */
enum class ThemeMode(val wire: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        val DEFAULT = DARK

        fun of(wire: String?): ThemeMode = entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

/**
 * The application's theme: colours, type, and shape, in one place.
 *
 * Material 3's own baseline is a phone theme — purple, generously spaced, sized for
 * a thumb. Everything here is a deliberate departure from it in the direction this
 * application actually is: a dense desktop tool where the interesting pixels belong
 * to the user's data and the chrome should get out of the way.
 *
 * Two colour systems are provided, not one. Material's scheme covers what its own
 * components read — buttons, fields, dialogs. [DbideColors] covers what this
 * application draws by hand and Material has no word for: a hairline between panes,
 * a hovered row, a zebra stripe, an editor keyword. Putting those in a second
 * palette rather than borrowing `surfaceVariant` for all of them is what keeps a
 * change to one from silently moving the other four.
 */
@Composable
fun DbideTheme(
    mode: ThemeMode = ThemeMode.DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalDbideColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = DenseTypography,
            shapes = ToolShapes,
            content = content,
        )
    }
}

/** Reads the theme the way `MaterialTheme` does, so call sites look the same. */
object Dbide {
    val colors: DbideColors
        @Composable @ReadOnlyComposable get() = LocalDbideColors.current
}

/**
 * The colours Material has no name for.
 *
 * Every one of these was previously either a hardcoded hex or `surfaceVariant`
 * pressed into a job it was not chosen for. Naming them by their use rather than by
 * their appearance is what makes the dark palette possible at all: "the colour a
 * hovered row goes" has an answer in both themes, "light grey" does not.
 */
@Immutable
data class DbideColors(
    /** The application shell, one step behind the panes it frames. */
    val chrome: Color,
    /** A pane's header strip: the tree's title row, the grid's column headers. */
    val paneHeader: Color,
    /** Every divider in the application. Dimmer than Material's, on purpose. */
    val hairline: Color,
    /** A row under the pointer. */
    val hover: Color,
    /** Alternating result rows, so a wide row stays readable across forty columns. */
    val stripe: Color,
    /** Behind the editor's line numbers. */
    val gutter: Color,
    /** The line numbers themselves. */
    val gutterText: Color,
    /** The line the caret is on. */
    val currentLine: Color,
    /** A keyboard focus ring, on the controls that draw their own. */
    val focusRing: Color,
    /** A connection that answered, an export that finished: the non-error good news. */
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** The editor's syntax palette. */
    val syntax: SyntaxColors,
)

/** What the SQL lexer's token kinds are drawn as. */
@Immutable
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val identifier: Color,
    val parameter: Color,
)

/**
 * The default outside [DbideTheme].
 *
 * A composable rendered without the theme — in a test, or in a preview — gets the
 * dark palette rather than a crash or a set of transparent holes.
 */
val LocalDbideColors = staticCompositionLocalOf { DarkExtras }

// --- Dark ------------------------------------------------------------------

/**
 * Neutral slate rather than pure black or Material's tinted greys.
 *
 * Black would be higher contrast and worse: a result grid is thousands of thin
 * light glyphs, and on true black they smear. The surfaces step by roughly one
 * value each, which is what lets a pane, its header, and the shell behind it all be
 * distinguishable without a border between any of them.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6BA1FF),
    onPrimary = Color(0xFF07214C),
    primaryContainer = Color(0xFF21395F),
    onPrimaryContainer = Color(0xFFCFE1FF),
    secondary = Color(0xFF9FB4CE),
    onSecondary = Color(0xFF17293D),
    // The selected row and the focused cell. Blue enough to read as a selection,
    // dim enough that a hundred selected rows are not a wall of colour.
    secondaryContainer = Color(0xFF2B3646),
    onSecondaryContainer = Color(0xFFDAE5F5),
    // Staging: amber, which is neither the safe grey of dev nor the red of prod.
    tertiary = Color(0xFFE3A649),
    onTertiary = Color(0xFF3D2A00),
    tertiaryContainer = Color(0xFF4A3A17),
    onTertiaryContainer = Color(0xFFFFE0AE),
    background = Color(0xFF15171C),
    onBackground = Color(0xFFE2E6ED),
    surface = Color(0xFF1A1D23),
    onSurface = Color(0xFFE2E6ED),
    surfaceVariant = Color(0xFF23272F),
    onSurfaceVariant = Color(0xFF98A1AE),
    surfaceContainerHighest = Color(0xFF2A2F38),
    outline = Color(0xFF4A525E),
    outlineVariant = Color(0xFF2C323B),
    // Production red has to survive being a small badge on a dark background, which
    // is why it is lighter than a warning colour would normally be.
    error = Color(0xFFFF8078),
    onError = Color(0xFF4A0F0A),
    errorContainer = Color(0xFF5A2320),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val DarkExtras = DbideColors(
    chrome = Color(0xFF12151A),
    paneHeader = Color(0xFF1E222A),
    hairline = Color(0xFF2C323B),
    hover = Color(0x0DFFFFFF),
    stripe = Color(0x0BFFFFFF),
    gutter = Color(0xFF1A1D23),
    gutterText = Color(0xFF5D6673),
    currentLine = Color(0x0AFFFFFF),
    focusRing = Color(0xFF6BA1FF),
    success = Color(0xFF5FC894),
    successContainer = Color(0xFF16382A),
    onSuccessContainer = Color(0xFFBFEDD5),
    syntax = SyntaxColors(
        keyword = Color(0xFF7FB3FF),
        string = Color(0xFF98C379),
        comment = Color(0xFF6B7583),
        number = Color(0xFFE5A15C),
        identifier = Color(0xFFC6A0F6),
        parameter = Color(0xFFE5C07B),
    ),
)

// --- Light -----------------------------------------------------------------

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B62D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF0A2C63),
    secondary = Color(0xFF4C5F78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE7F7),
    onSecondaryContainer = Color(0xFF16324F),
    tertiary = Color(0xFF8A5B08),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBE7C2),
    onTertiaryContainer = Color(0xFF4A3100),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF191C21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C21),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF5A6472),
    surfaceContainerHighest = Color(0xFFE4E8EE),
    outline = Color(0xFFA7B0BD),
    outlineVariant = Color(0xFFDFE3E9),
    error = Color(0xFFC13A2E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE0DC),
    onErrorContainer = Color(0xFF5E140D),
)

/**
 * The light palette's syntax colours are the ones this editor already shipped with.
 *
 * They were chosen against a white background and they work there; what the dark
 * theme needed was a second set, not a replacement for these.
 */
private val LightExtras = DbideColors(
    chrome = Color(0xFFEDEFF3),
    paneHeader = Color(0xFFF2F4F7),
    hairline = Color(0xFFDFE3E9),
    hover = Color(0x0A000000),
    stripe = Color(0x05000000),
    gutter = Color(0xFFF2F4F7),
    gutterText = Color(0xFF8C95A3),
    currentLine = Color(0x08000000),
    focusRing = Color(0xFF1B62D6),
    success = Color(0xFF1E7A4C),
    successContainer = Color(0xFFD7F0E2),
    onSuccessContainer = Color(0xFF0E3B25),
    syntax = SyntaxColors(
        keyword = Color(0xFF0B5FA5),
        string = Color(0xFF1B7A3D),
        comment = Color(0xFF7A7A7A),
        number = Color(0xFF9A4B00),
        identifier = Color(0xFF6A3FB5),
        parameter = Color(0xFF9A4B00),
    ),
)

// --- Type and shape --------------------------------------------------------

/**
 * Material's scale, one to two points down and tightened.
 *
 * The baseline is drawn for arm's length on a phone. At desk distance on a 13-inch
 * laptop it costs about four result rows per screen, which is four rows of the only
 * thing the user opened the application to look at.
 *
 * Line height is cut harder than size, because the scale's generous leading is what
 * makes a list of forty schema names read as forty paragraphs.
 */
private val DenseTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
        // Badges and status lines. Letter-spaced, because it is frequently set in
        // capitals and capitals set tight are a smear at this size.
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )
}

/**
 * Corners a tool has rather than corners an app has.
 *
 * Material's default rounds a button at 20dp, which reads as friendly and reads as
 * a phone. Everything here is 3–8dp: enough that nothing is a hard rectangle,
 * little enough that a dense row of controls still forms a straight line.
 */
private val ToolShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)
