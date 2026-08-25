package dev.caracal.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.caracal.app.EditorViewModel
import dev.caracal.app.ErrorMarker
import dev.caracal.app.FocusRequest
import dev.caracal.app.Shortcut
import dev.caracal.app.Shortcuts
import dev.caracal.core.sql.BracketPair
import dev.caracal.core.sql.SqlHighlighting
import dev.caracal.core.sql.SqlToken
import dev.caracal.core.sql.TokenKind

/**
 * The SQL editor.
 *
 * **A deviation from plan §5.8, recorded here because it is load-bearing.** The plan
 * recommends RSyntaxTextArea inside a `SwingPanel` and names `BasicTextField` as the
 * fallback. This is the fallback, chosen deliberately: a `SwingPanel` renders in a
 * layer of its own, which means Compose cannot draw over it — the confirmation dialog
 * §2.4 needs, and any popup after it, would clip against the editor's rectangle — and
 * it is invisible to `ComposeUiTest`, so every behaviour §2.3 specifies would have to
 * be verified by hand forever. The cost of the choice is that highlighting re-runs on
 * the whole document per keystroke, which is why [SqlHighlighting.DEFAULT_LIMIT]
 * exists. Search and code folding are what was given up; M4 owns the keyboard work.
 *
 * What the editor draws that a plain text field does not: PostgreSQL syntax colours,
 * line numbers that survive wrapping, the matching bracket around the caret, the
 * span Run is about to send shaded before it is sent, and — §2.9 — the character
 * the server pointed at when it refused the last one.
 */
@Composable
fun SqlEditor(
    model: EditorViewModel,
    modifier: Modifier = Modifier,
    shortcuts: Shortcuts = remember { Shortcuts() },
    focus: FocusRequest = remember { FocusRequest() },
) {
    Column(modifier = modifier.fillMaxSize().testTag("sql-editor")) {
        EditorToolbar(model, shortcuts)
        Hairline()
        EditorText(model, shortcuts, focus, modifier = Modifier.weight(1f))
    }
}

/**
 * Run, Cancel, and a sentence saying which statement Run means.
 *
 * The sentence is the important half. `Run` in a script of six statements is an
 * ambiguous button, and the label is what removes the ambiguity before the click
 * rather than after it.
 */
@Composable
private fun EditorToolbar(model: EditorViewModel, shortcuts: Shortcuts) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Sizes.paneHeader)
            .background(Caracal.colors.paneHeader)
            .padding(start = Space.lg, end = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = model.runLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("editor-run-label"),
        )
        if (model.running) {
            ToolButton(
                text = "Cancel",
                onClick = model::cancel,
                tag = "editor-cancel",
                emphasis = ToolEmphasis.DANGER,
                tooltip = "${Shortcut.CANCEL.action}  ${shortcuts.chord(Shortcut.CANCEL)}",
            )
        }
        // The chord is on the button rather than only in the documentation nobody
        // opens, and it is spelled by [Shortcut] rather than here — a label that
        // disagreed with the handler would be an instruction that does not work.
        ToolButton(
            text = "Run  ${shortcuts.chord(Shortcut.RUN)}",
            onClick = model::execute,
            tag = "editor-run",
            enabled = model.runnable,
            emphasis = ToolEmphasis.PRIMARY,
            tooltip = Shortcut.RUN.detail,
        )
    }
}

/** The document itself, with its gutter, sharing one vertical scroll. */
@Composable
private fun EditorText(
    model: EditorViewModel,
    shortcuts: Shortcuts,
    focus: FocusRequest,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val colors = editorColors()
    var layout: TextLayoutResult? by remember { mutableStateOf(null) }
    val scroll = rememberScrollState()
    val caretHere = remember { FocusRequester() }

    // §4.5's "move focus to the most relevant editor". The request is picked up here
    // rather than made by the switcher, because the switcher closes before this pane
    // exists — the connection it chose may still be being dialled — and it is taken
    // rather than merely read, so coming back to this tab later does not steal the
    // caret from wherever the user has since put it.
    LaunchedEffect(focus.pending) { if (focus.consume()) caretHere.requestFocus() }

    val script = model.text.text
    val caret = model.text.selection.end
    val tokens = remember(script) { SqlHighlighting.tokens(script) }
    val bracket = remember(script, caret) {
        if (model.text.selection.collapsed) SqlHighlighting.matchingBracket(script, caret) else null
    }
    val target = model.target
    // Where the server said the problem was, if it said and if the script still
    // reads the way it read when it was sent. §4.6: the model answers that, so the
    // underline here and the sentence under the result cannot disagree about it.
    val errorAt = (model.marker as? ErrorMarker.At)?.index
    val styled = remember(script, tokens, bracket, target, errorAt, colors) {
        annotate(script, tokens, bracket, target?.start, target?.end, errorAt, colors)
    }
    // The transformation adds colour and shading and nothing else, so every offset in
    // the drawn text is the same offset in the document and the mapping is the
    // identity. Anything else here would move the caret away from the character it is
    // actually beside.
    val transformation = remember(styled) {
        VisualTransformation { TransformedText(styled, OffsetMapping.Identity) }
    }

    // §2.9 asks for the position to be scrolled to, not merely coloured. A failure
    // reported at line 200 of a script is a failure nobody can see, and "the error is
    // highlighted" is only true if the highlight is on screen.
    LaunchedEffect(errorAt, layout) {
        val result = layout ?: return@LaunchedEffect
        val index = errorAt ?: return@LaunchedEffect
        val top = result.getLineTop(result.getLineForOffset(index)).toInt()
        if (top !in scroll.value..(scroll.value + result.size.height / 2)) {
            scroll.animateScrollTo(top.coerceAtMost(scroll.maxValue))
        }
    }

    // Which line the caret is on, for the gutter to mark. Counted rather than read
    // off the layout so it is a document line and not a wrapped visual one.
    val caretLine = remember(script, caret) {
        if (caret in 0..script.length) script.take(caret).count { it == '\n' } + 1 else 0
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(scroll),
    ) {
        Gutter(layout = layout, script = script, style = style, caretLine = caretLine)
        VerticalHairline(modifier = Modifier.fillMaxHeight())
        BasicTextField(
            value = model.text,
            onValueChange = model::edit,
            textStyle = style,
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .focusRequester(caretHere)
                .padding(horizontal = Space.md, vertical = Space.sm)
                // Preview, and only for Run: the chord has to beat the field's own
                // handling of Enter, which would otherwise put a newline in the
                // script. Every other chord is left to bubble past the field to the
                // workspace, which is what lets a text field keep the keys it needs.
                .onPreviewKeyEvent { event ->
                    if (Shortcut.of(event, shortcuts.platform) != Shortcut.RUN) {
                        return@onPreviewKeyEvent false
                    }
                    model.execute()
                    true
                }
                .testTag("editor-text"),
        )
    }
}

/**
 * Line numbers, drawn from the text field's own layout.
 *
 * Not a column of `Text`s beside the field: a wrapped line occupies two visual lines
 * and one number, and only the layout knows which visual lines those are. Reading it
 * is also what keeps the numbers aligned when the font metrics change under a
 * different theme.
 */
@Composable
private fun Gutter(
    layout: TextLayoutResult?,
    script: String,
    style: TextStyle,
    caretLine: Int,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val numberStyle = style.copy(color = Caracal.colors.gutterText)
    // The line the caret is on is drawn at full strength, and its band is painted
    // across the gutter. It is the cheapest possible answer to "where am I?" in a
    // script long enough that the caret is off screen.
    val currentStyle = style.copy(color = MaterialTheme.colorScheme.onSurface)
    val currentBand = Caracal.colors.currentLine
    val lines = remember(script) { script.count { it == '\n' } + 1 }

    val width = remember(lines, numberStyle, density) {
        with(density) { (measurer.measure(lines.toString(), numberStyle).size.width + 24).toDp() }
    }
    val height = with(density) { (layout?.size?.height ?: 0).toDp() }

    Canvas(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(Caracal.colors.gutter)
            .testTag("editor-gutter"),
    ) {
        val result = layout ?: return@Canvas
        var number = 0
        for (line in 0 until result.lineCount) {
            val start = result.getLineStart(line)
            // A visual line that continues a wrapped one carries no number of its own.
            if (line > 0 && script.getOrNull(start - 1) != '\n') continue
            number++
            val current = number == caretLine
            val top = result.getLineTop(line)
            if (current) {
                drawRect(
                    color = currentBand,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, result.getLineBottom(line) - top),
                )
            }
            val text = measurer.measure(number.toString(), if (current) currentStyle else numberStyle)
            drawText(
                textLayoutResult = text,
                topLeft = Offset(
                    x = size.width - text.size.width - 12.dp.toPx(),
                    // The field's own top padding, so number and line share a baseline.
                    y = top + 4.dp.toPx(),
                ),
            )
        }
    }
}

/** The colours the editor draws with, resolved once against the theme. */
private data class EditorColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val identifier: Color,
    val parameter: Color,
    val target: Color,
    val bracket: Color,
    val error: Color,
)

/**
 * The syntax palette comes from the theme, not from this file.
 *
 * It used to be six literal hex values chosen against white. They were correct
 * there and unreadable anywhere else — a dark theme with a navy keyword on a near
 * black background is not a dark theme, it is a broken one. [SyntaxColors] holds the
 * two sets; this only decides how the editor's own shading sits over them.
 */
@Composable
private fun editorColors(): EditorColors {
    val scheme = MaterialTheme.colorScheme
    val syntax = Caracal.colors.syntax
    return remember(scheme, syntax) {
        EditorColors(
            keyword = syntax.keyword,
            string = syntax.string,
            comment = syntax.comment,
            number = syntax.number,
            identifier = syntax.identifier,
            parameter = syntax.parameter,
            // The statement about to run, shaded rather than outlined: an outline
            // between two adjacent statements is one line the eye has to attribute,
            // and shading is unambiguous about which side it belongs to.
            target = scheme.primary.copy(alpha = 0.13f),
            bracket = scheme.primary.copy(alpha = 0.30f),
            error = scheme.error,
        )
    }
}

/**
 * The script as one styled string: the shaded execution span underneath, syntax
 * colours over it, and the matching brackets on top.
 *
 * The order is the point. Spans are applied in sequence, so the last one wins where
 * they overlap, and a bracket the caret is on stays visible inside a statement that
 * is already shaded.
 */
private fun annotate(
    script: String,
    tokens: List<SqlToken>,
    bracket: BracketPair?,
    targetStart: Int?,
    targetEnd: Int?,
    errorAt: Int?,
    colors: EditorColors,
): AnnotatedString = buildAnnotatedString {
    append(script)
    if (targetStart != null && targetEnd != null && targetEnd > targetStart) {
        addStyle(SpanStyle(background = colors.target), targetStart, minOf(targetEnd, script.length))
    }
    tokens.forEach { token ->
        addStyle(token.style(colors), token.start, minOf(token.end, script.length))
    }
    bracket?.let { pair ->
        val style = SpanStyle(background = colors.bracket, fontWeight = FontWeight.Bold)
        addStyle(style, pair.open, pair.open + 1)
        addStyle(style, pair.close, pair.close + 1)
    }
    // Last, so it wins wherever it lands. A syntax error is frequently reported on a
    // keyword, and a keyword already has a colour of its own.
    // A position one past the last character is where PostgreSQL reports an error at
    // end of input. It is scrolled to and it is not underlined: there is no character
    // there, and underlining the one before it would be pointing at the wrong thing.
    errorAt?.takeIf { it in script.indices }?.let { index ->
        addStyle(
            SpanStyle(
                color = colors.error,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
                background = colors.error.copy(alpha = 0.14f),
            ),
            index,
            index + 1,
        )
    }
}

private fun SqlToken.style(colors: EditorColors): SpanStyle = when (kind) {
    TokenKind.KEYWORD -> SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)
    TokenKind.STRING -> SpanStyle(color = colors.string)
    TokenKind.COMMENT -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
    TokenKind.NUMBER -> SpanStyle(color = colors.number)
    TokenKind.QUOTED_IDENTIFIER -> SpanStyle(color = colors.identifier)
    TokenKind.PARAMETER -> SpanStyle(color = colors.parameter, fontWeight = FontWeight.Bold)
}
