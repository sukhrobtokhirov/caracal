package dev.dbide.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dbide.core.connections.Engine

/**
 * The engines' own marks, drawn rather than shipped.
 *
 * A logo is the fastest identifier a list of servers has: a user with nine
 * connections finds the cache by its colour before they have read a single name, and
 * they do it in the corner of their eye while the pointer is already moving. The
 * `POSTGRES` badge is still there in the detail pane, because a mark is a shortcut
 * and never the only statement of what something is.
 *
 * Drawn as vectors on a [Canvas] for three reasons that all amount to the same one.
 * They stay sharp at every scale factor a mixed-DPI desk throws at them; they carry
 * no bitmap into the jar and no licence question with it; and they take their
 * silhouette from the brand while being unmistakably this application's rendering of
 * it, rather than a copy of an asset the projects publish under their own terms.
 *
 * Both are laid out in a 100×100 box and scaled to whatever [EngineLogo] is given, so
 * every measurement below reads as a percentage of the mark.
 */
object BrandColors {
    /** PostgreSQL's elephant blue. */
    val postgres = Color(0xFF336791)
    val postgresLight = Color(0xFF6E9CC4)

    /** Redis red, and the shade its stacked layers put their sides in. */
    val redis = Color(0xFFD82C20)
    val redisShadow = Color(0xFF9E2018)

    fun of(engine: Engine): Color = when (engine) {
        Engine.POSTGRES -> postgres
        Engine.REDIS -> redis
    }
}

/**
 * One engine's mark at [size].
 *
 * [described] is what decides whether the mark is a statement or decoration. Where
 * the logo has replaced the `POSTGRES` badge — the sidebar row — it is the only thing
 * on the row saying which engine this is, so it announces itself exactly as the badge
 * did. Where the badge or a labelled control is still beside it, it leaves the
 * accessibility tree instead of saying the same thing twice.
 */
@Composable
fun EngineLogo(
    engine: Engine,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    described: Boolean = false,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (described) {
                    Modifier.testTag("engine-${engine.wire}")
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
    ) {
        when (engine) {
            Engine.POSTGRES -> drawElephant()
            Engine.REDIS -> drawStack()
        }
    }
}

/**
 * The mark in a tinted tile, for the places that are choosing an engine rather than
 * reporting one — the dialog's rail, and a detail pane's heading.
 *
 * The tile is the engine's own colour at low alpha, which is what makes two of them
 * side by side read as two options of one kind rather than as two unrelated pictures.
 */
@Composable
fun EngineTile(
    engine: Engine,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    selected: Boolean = false,
) {
    val brand = BrandColors.of(engine)
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(brand.copy(alpha = if (selected) 0.20f else 0.12f))
            .border(
                Sizes.hairline,
                if (selected) brand.copy(alpha = 0.65f) else Dbide.colors.hairline,
                MaterialTheme.shapes.medium,
            ),
        contentAlignment = Alignment.Center,
    ) {
        EngineLogo(engine, size = size * 0.62f)
    }
}

// --- PostgreSQL ------------------------------------------------------------

/**
 * An elephant's head, front on.
 *
 * Front on rather than in profile because this has to survive being 16 device-
 * independent pixels in a sidebar row, and at that size a profile is a blue smudge
 * while a symmetric silhouette — two ears, a trunk down the middle, two eyes — still
 * reads as an animal. The parts are drawn back to front: ears, then head over the
 * inner edge of them, then the trunk, then the eyes on top.
 */
private fun DrawScope.drawElephant() {
    val s = size.minDimension / 100f
    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    // The ears: wide, set high, and reaching past the head on both sides. They are
    // what makes the silhouette an elephant rather than a blue oval — an ear that
    // stops where the head stops leaves one blob, so these end well outside it.
    listOf(21f, 79f).forEach { cx ->
        drawOval(
            color = BrandColors.postgres,
            topLeft = p(cx - 23f, 15f),
            size = Size(46f * s, 48f * s),
        )
        // Inner ear, a shade up. Invisible at sidebar size and the thing that stops
        // the mark reading as flat in the dialog's header.
        drawOval(
            color = BrandColors.postgresLight.copy(alpha = 0.45f),
            topLeft = p(cx - 13f, 25f),
            size = Size(26f * s, 30f * s),
        )
    }

    // The head, narrower than the ears and reaching lower, so the outline steps in
    // before the trunk leaves it.
    drawOval(
        color = BrandColors.postgres,
        topLeft = p(28f, 14f),
        size = Size(44f * s, 58f * s),
    )

    // The trunk, as one stroked curve with a round cap rather than a filled outline.
    // A tube that bends is read as a trunk at 14 device-independent pixels; a
    // tapering silhouette at that size is a smudge.
    val trunk = Path().apply {
        moveTo(50f * s, 60f * s)
        cubicTo(45f * s, 78f * s, 50f * s, 92f * s, 60f * s, 94f * s)
    }
    drawPath(
        trunk,
        BrandColors.postgres,
        style = Stroke(width = 15f * s, cap = StrokeCap.Round),
    )

    // Eyes, high and close together. White rather than a darker blue: at 14dp a
    // low-contrast eye disappears and the mark goes back to being a shape.
    listOf(39f, 61f).forEach { cx ->
        drawCircle(color = Color.White, radius = 5f * s, center = p(cx, 40f))
        drawCircle(color = BrandColors.postgres, radius = 2.2f * s, center = p(cx, 41f))
    }
}

// --- Redis -----------------------------------------------------------------

/**
 * Three stacked layers seen from slightly above.
 *
 * Redis's mark is a database drawn the way a database is drawn on a whiteboard, and
 * the stack is what carries it: each layer is a diamond top face with a darker skirt
 * under it, drawn bottom first so the one above overlaps the one below. The pair of
 * light notches on the top face is the mark's own detail and the thing that keeps it
 * from reading as a generic cylinder.
 */
private fun DrawScope.drawStack() {
    val s = size.minDimension / 100f
    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    // Bottom to top, so each layer's skirt is hidden by the layer beneath it.
    listOf(72f, 50f, 28f).forEach { centre ->
        val skirt = Path().apply {
            moveTo(6f * s, centre * s)
            lineTo(50f * s, (centre + 16f) * s)
            lineTo(94f * s, centre * s)
            lineTo(94f * s, (centre + 10f) * s)
            lineTo(50f * s, (centre + 26f) * s)
            lineTo(6f * s, (centre + 10f) * s)
            close()
        }
        drawPath(skirt, BrandColors.redisShadow)

        val face = Path().apply {
            moveTo(50f * s, (centre - 16f) * s)
            lineTo(94f * s, centre * s)
            lineTo(50f * s, (centre + 16f) * s)
            lineTo(6f * s, centre * s)
            close()
        }
        drawPath(face, BrandColors.redis)

        // The two notches, as short strokes along the face's own diagonal.
        drawLine(
            color = Color.White.copy(alpha = 0.85f),
            start = p(30f, centre - 1f),
            end = p(44f, centre + 4f),
            strokeWidth = 3.5f * s,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.55f),
            start = p(58f, centre - 5f),
            end = p(72f, centre),
            strokeWidth = 3.5f * s,
        )
    }
}

/**
 * The application's own mark: a prompt in a box.
 *
 * Used in the shell and in the settings window, where the application has to say what
 * it is before it has a connection to name. A caret and an underscore rather than a
 * database cylinder, because a cylinder is what every one of these connects *to* — the
 * thing this window actually is, is the place you type at them from.
 */
@Composable
fun AppMark(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier.size(size).testTag("app-mark")) {
        val s = this.size.minDimension / 100f
        drawRoundRect(
            color = dim.copy(alpha = 0.6f),
            topLeft = Offset(6f * s, 6f * s),
            size = Size(88f * s, 88f * s),
            cornerRadius = CornerRadius(22f * s, 22f * s),
            style = Stroke(width = 8f * s),
        )
        val caret = Path().apply {
            moveTo(30f * s, 34f * s)
            lineTo(48f * s, 50f * s)
            lineTo(30f * s, 66f * s)
        }
        drawPath(
            caret,
            accent,
            style = Stroke(width = 9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(
            color = accent,
            start = Offset(56f * s, 66f * s),
            end = Offset(72f * s, 66f * s),
            strokeWidth = 9f * s,
            cap = StrokeCap.Round,
        )
    }
}
