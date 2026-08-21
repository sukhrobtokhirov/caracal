package dev.caracal.app.ui

import androidx.compose.ui.unit.dp

/**
 * The measurements the whole application lays out against.
 *
 * They exist because the alternative was what this file replaced: a `20.dp` here, a
 * `14.dp` there, and no way to answer "how far apart should these be?" except by
 * looking at what the last screen happened to use. A tool that shows a schema tree
 * beside a grid beside an editor has three panes whose padding has to agree, and
 * agreement is easier to keep when there is one place to disagree with.
 *
 * The scale is deliberately tight. This is a window someone keeps open all day next
 * to a terminal, and every row of padding is a row of data they cannot see.
 */
object Space {
    /** Between a label and the thing it labels. */
    val xs = 2.dp

    /** Inside a control: a badge's own padding, the gap between an icon and its word. */
    val sm = 4.dp

    /** The default gap between two adjacent things that belong together. */
    val md = 8.dp

    /** Pane padding, and the gap between two things that merely sit near each other. */
    val lg = 12.dp

    /** Between sections of a form or a detail pane. */
    val xl = 16.dp

    /** The margin around a page that is a page rather than a pane. */
    val xxl = 24.dp
}

/**
 * Fixed sizes shared across panes.
 *
 * Row heights in particular are a density decision rather than a per-file one: the
 * tree, the grid, and the connection list should scan as one application, and they
 * only do that if a row is a row everywhere.
 */
object Sizes {
    /** The application shell across the top. */
    val barHeight = 36.dp

    /** A pane's own header strip — the tree's "Database", the editor's toolbar. */
    val paneHeader = 32.dp

    /** One line in the schema tree or the connection list. */
    val treeRow = 26.dp

    /**
     * The triangle that opens a node, and the square it can be hit anywhere inside.
     *
     * Sized as a target rather than as a glyph. A 10dp arrow is a legible arrow and
     * an unhittable button, and the button is the half of it that gets used a
     * hundred times an hour.
     */
    val disclosure = 20.dp

    /** One result row. Tighter than a tree row: there are ten thousand of them. */
    val gridRow = 24.dp

    /** The grid header, which carries a column name over its type. */
    val gridHeader = 38.dp

    /** The connection sidebar. */
    val sidebar = 264.dp

    /** The object browser, which holds longer names than the sidebar. */
    val browser = 288.dp

    /**
     * The Redis key browser.
     *
     * Wider than the schema tree because what it holds is wider: a schema tree row is
     * one identifier, and a key row is a name, a type, a countdown, and a size.
     */
    val keyBrowser = 340.dp

    /**
     * The rail down the left of a dialog.
     *
     * Wider than a sidebar row needs, because a rail entry is two lines: what the
     * section is, and one line saying what choosing it means. "PostgreSQL" over
     * "Relational, port 5432" is a choice someone can make without opening it first.
     */
    val dialogRail = 220.dp

    /** The accent stripe marking the selected row in a list. */
    val selectionStripe = 2.dp

    /** A hairline. Compose's dividers default to a heavier rule than a dense UI wants. */
    val hairline = 1.dp

    /**
     * The least room the editor and its result are allowed to be squeezed into.
     *
     * The side panes are fixed widths, and fixed widths do not fit inside every
     * window: 264 of sidebar and 288 of object browser want 552 before anything is
     * drawn, and a small laptop at 200% display scale has 380 in total. Past this
     * line the panes give way rather than pushing Run off the right-hand edge —
     * which is what they did, silently, because Compose lays a pane out past the
     * edge without complaining about it.
     *
     * 420 is roughly a `select` and its result at a legible width. It is a
     * judgement, not a measurement, and the two zoom tests are what hold it honest.
     */
    val workbenchMin = 420.dp
}
