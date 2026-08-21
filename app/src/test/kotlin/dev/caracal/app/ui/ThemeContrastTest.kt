package dev.caracal.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * §4.8's contrast requirement, as arithmetic rather than as an opinion.
 *
 * Two palettes times forty-odd pairings is more than anyone re-checks by eye after
 * nudging a colour, which is exactly how a theme drifts below the floor one commit
 * at a time. WCAG 2.1 relative luminance is twelve lines of maths and the palettes
 * are plain values, so the check is a unit test: no window, no renderer, no
 * screenshot to re-bless.
 *
 * The two floors are 1.4.3 and 1.4.11. Text a user reads owes 4.5:1. A boundary that
 * is the only thing identifying a control owes 3:1 — which is why `outline` is held
 * to it and `hairline` is not: `hairline` draws dividers, and a divider between two
 * panes identifies nothing.
 */
class ThemeContrastTest {

    @TestFactory
    fun `every readable pairing meets WCAG AA`(): List<DynamicTest> =
        listOf(Palette.dark(), Palette.light()).flatMap { it.checks() }.map { check ->
            DynamicTest.dynamicTest(check.name) {
                val actual = contrast(check.foreground, check.background)
                assertTrue(
                    actual >= check.floor,
                    "${check.name}: ${"%.2f".format(actual)}:1, needs ${check.floor}:1",
                )
            }
        }

    /**
     * The one pairing that is deliberately below the floor, asserted as such.
     *
     * A divider is exempt, and it should stay exempt by decision rather than by
     * nobody having looked: if someone raises `hairline` to a colour that would pass,
     * they have made every pane in the application shout, and this fails to say so.
     */
    @TestFactory
    fun `dividers stay quieter than a control boundary`(): List<DynamicTest> =
        listOf(Palette.dark(), Palette.light()).map { palette ->
            DynamicTest.dynamicTest("${palette.name}: hairline is dimmer than outline") {
                val hairline = contrast(palette.extras.hairline, palette.scheme.surface)
                val outline = contrast(palette.scheme.outline, palette.scheme.surface)
                assertTrue(
                    hairline < outline,
                    "hairline ${"%.2f".format(hairline)}:1 is not quieter than " +
                        "outline ${"%.2f".format(outline)}:1",
                )
            }
        }
}

private class Check(val name: String, val foreground: Color, val background: Color, val floor: Double)

/** One theme, and the surfaces anything in it can land on. */
private class Palette(val name: String, val scheme: ColorScheme, val extras: CaracalColors) {

    companion object {
        fun dark() = Palette("dark", DarkScheme, DarkExtras)

        fun light() = Palette("light", LightScheme, LightExtras)
    }

    /**
     * The surfaces a piece of text or a border can be drawn over.
     *
     * Every one of these is somewhere the application actually puts a border or a
     * secondary label, so a colour has to clear the floor against the worst of them
     * rather than against whichever one it was picked on.
     */
    private val surfaces = listOf(
        scheme.surface,
        scheme.background,
        scheme.surfaceVariant,
        extras.chrome,
        extras.paneHeader,
    )

    /** The editor, including the band under the caret — the line being read. */
    private val editorSurfaces =
        listOf(scheme.surface, extras.currentLine.over(scheme.surface))

    fun checks(): List<Check> = buildList {
        text("body text", scheme.onSurface, surfaces)
        text("secondary text", scheme.onSurfaceVariant, surfaces)
        text("line numbers", extras.gutterText, listOf(extras.gutter))

        text("syntax keyword", extras.syntax.keyword, editorSurfaces)
        text("syntax string", extras.syntax.string, editorSurfaces)
        text("syntax comment", extras.syntax.comment, editorSurfaces)
        text("syntax number", extras.syntax.number, editorSurfaces)
        text("syntax identifier", extras.syntax.identifier, editorSurfaces)
        text("syntax parameter", extras.syntax.parameter, editorSurfaces)

        // The badges that carry the safety signals. Production is the one that has to
        // survive being eleven bold points on a bar someone is not looking at.
        on("production badge", scheme.onErrorContainer, scheme.errorContainer)
        on("staging badge", scheme.onTertiaryContainer, scheme.tertiaryContainer)
        on("read-only badge", scheme.onSecondaryContainer, scheme.secondaryContainer)
        on("succeeded badge", extras.onSuccessContainer, extras.successContainer)
        on("cancelled badge", scheme.onSurfaceVariant, scheme.surfaceVariant)
        on("selected row", scheme.onSecondaryContainer, scheme.secondaryContainer)
        on("focused cell", scheme.onPrimaryContainer, scheme.primaryContainer)
        on("primary button", scheme.onPrimary, scheme.primary)

        // Non-text: a control's own boundary, and the dots and rules that stand for a
        // state on their own.
        ui("control boundary", scheme.outline, surfaces)
        ui("accent", scheme.primary, surfaces)
        ui("focus ring", extras.focusRing, surfaces)
        ui("error mark", scheme.error, surfaces)
        ui("success mark", extras.success, surfaces)
        ui("in-progress mark", scheme.tertiary, surfaces)
    }

    private fun MutableList<Check>.text(what: String, color: Color, over: List<Color>) =
        over.forEach { add(Check("$name: $what", color, it, 4.5)) }

    private fun MutableList<Check>.ui(what: String, color: Color, over: List<Color>) =
        over.forEach { add(Check("$name: $what", color, it, 3.0)) }

    private fun MutableList<Check>.on(what: String, color: Color, over: Color) =
        add(Check("$name: $what", color, over, 4.5))
}

/**
 * [this] composited over [background].
 *
 * The translucent tokens — the current-line band, the zebra stripe, the hover
 * highlight — are drawn on top of a surface rather than instead of it, so the colour
 * text has to survive is the blend and not the token.
 */
private fun Color.over(background: Color): Color = Color(
    red = red * alpha + background.red * (1 - alpha),
    green = green * alpha + background.green * (1 - alpha),
    blue = blue * alpha + background.blue * (1 - alpha),
)

/** WCAG 2.1 contrast ratio, 1:1 to 21:1. */
private fun contrast(a: Color, b: Color): Double {
    val one = luminance(a)
    val two = luminance(b)
    return (max(one, two) + 0.05) / (min(one, two) + 0.05)
}

/** WCAG 2.1 relative luminance. sRGB, linearized, weighted for the eye's response. */
private fun luminance(color: Color): Double =
    0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

private fun linear(channel: Float): Double {
    val value = channel.toDouble()
    return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}
