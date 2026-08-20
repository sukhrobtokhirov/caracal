package dev.dbide.app.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.dbide.app.EditorRun
import dev.dbide.app.EditorViewModel
import dev.dbide.core.sql.BracketPair
import dev.dbide.core.sql.SqlHighlighting
import dev.dbide.core.sql.SqlToken
import dev.dbide.core.sql.TokenKind

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
fun SqlEditor(model: EditorViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().semantics { contentDescription = "sql-editor" }) {
        EditorToolbar(model)
        HorizontalDivider()
        EditorText(model, modifier = Modifier.weight(1f))
    }
}

/** Run, Cancel, and a sentence saying which statement Run means. */
@Composable
private fun EditorToolbar(model: EditorViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = model.runLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.weight(1f).semantics { contentDescription = "editor-run-label" },
        )
        if (model.running) {
            TextButton(
                onClick = model::cancel,
                modifier = Modifier.semantics { contentDescription = "editor-cancel" },
            ) {
                Text("Cancel", style = MaterialTheme.typography.labelMedium)
            }
        }
        TextButton(
            onClick = model::execute,
            enabled = model.runnable,
            modifier = Modifier.semantics { contentDescription = "editor-run" },
        ) {
            Text("Run", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The document itself, with its gutter, sharing one vertical scroll. */
@Composable
private fun EditorText(model: EditorViewModel, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val colors = editorColors()
    var layout: TextLayoutResult? by remember { mutableStateOf(null) }
    val scroll = rememberScrollState()

    val script = model.text.text
    val caret = model.text.selection.end
    val tokens = remember(script) { SqlHighlighting.tokens(script) }
    val bracket = remember(script, caret) {
        if (model.text.selection.collapsed) SqlHighlighting.matchingBracket(script, caret) else null
    }
    val target = model.target
    // Where the server said the problem was, if it said. It survives editing on
    // purpose: a syntax error you are in the middle of fixing is exactly when you
    // want to still be able to see where it was.
    val errorAt = (model.run as? EditorRun.Failed)?.errorAt?.takeIf { it in script.indices }
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

    Row(modifier = modifier.fillMaxSize().verticalScroll(scroll)) {
        Gutter(layout = layout, script = script, style = style)
        VerticalDivider(modifier = Modifier.fillMaxHeight())
        BasicTextField(
            value = model.text,
            onValueChange = model::edit,
            textStyle = style,
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                // Preview, so the chord runs the statement instead of inserting a
                // newline into it. Meta is the macOS chord and Ctrl everywhere else;
                // both are accepted on both.
                .onPreviewKeyEvent { event ->
                    val running = event.type == KeyEventType.KeyDown &&
                        event.key == Key.Enter &&
                        (event.isCtrlPressed || event.isMetaPressed)
                    if (!running) return@onPreviewKeyEvent false
                    model.execute()
                    true
                }
                .semantics { contentDescription = "editor-text" },
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
private fun Gutter(layout: TextLayoutResult?, script: String, style: TextStyle) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val numberStyle = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val lines = remember(script) { script.count { it == '\n' } + 1 }

    val width = remember(lines, numberStyle, density) {
        with(density) { (measurer.measure(lines.toString(), numberStyle).size.width + 20).toDp() }
    }
    val height = with(density) { (layout?.size?.height ?: 0).toDp() }

    Canvas(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "editor-gutter" },
    ) {
        val result = layout ?: return@Canvas
        var number = 0
        for (line in 0 until result.lineCount) {
            val start = result.getLineStart(line)
            // A visual line that continues a wrapped one carries no number of its own.
            if (line > 0 && script.getOrNull(start - 1) != '\n') continue
            number++
            val text = measurer.measure(number.toString(), numberStyle)
            drawText(
                textLayoutResult = text,
                topLeft = Offset(
                    x = size.width - text.size.width - 10.dp.toPx(),
                    // The field's own top padding, so number and line share a baseline.
                    y = result.getLineTop(line) + 4.dp.toPx(),
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

@Composable
private fun editorColors(): EditorColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        EditorColors(
            keyword = Color(0xFF0B5FA5),
            string = Color(0xFF1B7A3D),
            comment = Color(0xFF7A7A7A),
            number = Color(0xFF9A4B00),
            identifier = Color(0xFF6A3FB5),
            parameter = Color(0xFF9A4B00),
            // The statement about to run, shaded rather than outlined: an outline
            // between two adjacent statements is one line the eye has to attribute,
            // and shading is unambiguous about which side it belongs to.
            target = scheme.primary.copy(alpha = 0.10f),
            bracket = scheme.primary.copy(alpha = 0.28f),
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
    errorAt?.let { index ->
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
