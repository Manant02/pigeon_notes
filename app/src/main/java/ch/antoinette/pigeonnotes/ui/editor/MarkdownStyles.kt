package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyle
import ch.antoinette.pigeonnotes.domain.markdown.ListLine
import ch.antoinette.pigeonnotes.domain.markdown.MathHit
import ch.antoinette.pigeonnotes.domain.markdown.Projection
import ch.antoinette.pigeonnotes.domain.markdown.StyleRange
import ch.antoinette.pigeonnotes.ui.theme.EditorType

/**
 * Theme colours and text measurement resolved once per composition; maps abstract inline styles to
 * SpanStyles and list lines to hanging indents.
 */
class MarkdownPalette(
    val text: Color,
    val link: Color,
    val codeBackground: Color,
    val muted: Color,
    val bullet: Color,
    val density: Density,
    /** Width of [prefix] laid out in [style], in sp — used for hanging indents. */
    private val measure: (prefix: String, style: TextStyle) -> TextUnit,
) {
    private val widths = HashMap<String, TextUnit>()

    fun spanStyle(s: StyleRange): SpanStyle? = when (s.style) {
        InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        InlineStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough, color = muted)
        InlineStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, fontSize = 15.sp)
        InlineStyle.MATH -> SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = link)
        InlineStyle.LINK -> SpanStyle(color = link, textDecoration = TextDecoration.Underline)
        InlineStyle.IMAGE_ALT -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
        InlineStyle.QUOTE -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
        InlineStyle.BULLET -> SpanStyle(color = bullet, fontWeight = FontWeight.Medium)
        InlineStyle.DIM -> SpanStyle(color = muted)
        InlineStyle.HEADING -> null // block-level: applied through the base TextStyle
    }

    /** Width of a list item's bullet/checkbox prefix, so wrapped lines can hang under the text. */
    fun indentWidth(prefix: String, style: TextStyle): TextUnit =
        widths.getOrPut(prefix + "|" + style.fontSize.value) { measure(prefix, style) }

    /** Wrapped lines of a list item continue under the text, not under the bullet. */
    fun hangingIndent(line: ListLine, text: String, style: TextStyle): ParagraphStyle? {
        val prefix = text.substring(line.start, line.contentStart.coerceIn(line.start, line.end))
        if (prefix.isEmpty()) return null
        return ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = indentWidth(prefix, style)))
    }

    /** Inline content for one formula: the rendered bitmap, or its source when TeX rejects it. */
    private fun mathContent(hit: MathHit, style: TextStyle): InlineTextContent {
        val sizePx = with(density) { style.fontSize.toPx() }
        val rendered = Latex.render(hit.latex, sizePx, text)
        if (rendered != null) {
            val w = with(density) { rendered.widthPx.toSp() }
            val h = with(density) { rendered.heightPx.toSp() }
            return InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) {
                Image(rendered.bitmap, contentDescription = hit.latex)
            }
        }
        val src = "$" + hit.latex + "$"
        val w = measure(src, style.copy(fontFamily = FontFamily.Monospace))
        return InlineTextContent(Placeholder(w, style.lineHeight, PlaceholderVerticalAlign.TextCenter)) {
            Text(src, style = style.copy(fontFamily = FontFamily.Monospace, color = muted), maxLines = 1)
        }
    }

    /**
     * One list item, rendered as its own Text so the spacing between items can differ from the
     * spacing between the wrapped lines inside an item. [outputStart] maps a tap back to [Projection].
     */
    class ListItemText(val outputStart: Int, val text: AnnotatedString, val inlineContent: Map<String, InlineTextContent>)

    /** Splits a list block into items (a bullet line plus its continuation lines). */
    fun annotateListItems(p: Projection, style: TextStyle): List<ListItemText> {
        val starts = p.listLines.map { it.start }
        if (starts.isEmpty()) return emptyList()
        val mathAt = p.math.associateBy { it.start }
        return p.listLines.mapIndexed { idx, line ->
            val from = line.start
            var to = if (idx + 1 < starts.size) starts[idx + 1] else p.text.length
            while (to > from && p.text[to - 1] == '\n') to--  // the separator is the gap between items
            val inline = HashMap<String, InlineTextContent>()
            val b = AnnotatedString.Builder(to - from + 8)
            val prefix = p.text.substring(from, line.contentStart.coerceIn(from, to))
            b.pushStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = indentWidth(prefix, style))))
            var i = from
            while (i < to) {
                val hit = mathAt[i]
                if (hit != null && hit.end <= to) {
                    val id = "math$i"
                    inline[id] = mathContent(hit, style)
                    b.appendInlineContent(id, p.text.substring(i, i + 1)) // one char, so offsets stay put
                    i = hit.end
                } else {
                    b.append(p.text[i]); i++
                }
            }
            b.pop()
            for (s in p.styles) {
                val span = spanStyle(s) ?: continue
                val a = s.start.coerceIn(from, to) - from
                val z = s.end.coerceIn(from, to) - from
                if (z > a) b.addStyle(span, a, z)
            }
            ListItemText(from, b.toAnnotatedString(), inline)
        }
    }

    /**
     * Display text for a rendered block. List lines become separate paragraphs (for hanging indents),
     * which means their terminating newlines are dropped; [toOutput] maps a tap offset back to the
     * projection's coordinates.
     */
    class Annotated(val text: AnnotatedString, val inlineContent: Map<String, InlineTextContent>, private val removed: IntArray) {
        fun toOutput(displayOffset: Int): Int {
            var o = displayOffset
            for (r in removed) { if (r <= o) o++ else break }
            return o
        }
    }

    /** Builds the display text for a rendered block: spans, hanging indents and inline formulas. */
    fun annotate(p: Projection, style: TextStyle): Annotated {
        val inline = HashMap<String, InlineTextContent>()
        val b = AnnotatedString.Builder(p.text.length + 16)
        val mathAt = p.math.associateBy { it.start }
        val lineEnds = p.listLines.map { it.end }.filter { it < p.text.length && p.text[it] == '\n' }.toHashSet()
        val removed = ArrayList<Int>()
        var i = 0
        while (i < p.text.length) {
            if (i in lineEnds) {
                removed += i; i++; continue
            }
            val hit = mathAt[i]
            if (hit != null) {
                val id = "math$i"
                inline[id] = mathContent(hit, style)
                b.appendInlineContent(id, p.text.substring(i, i + 1))
                i = hit.end
                continue
            }
            b.append(p.text[i]); i++
        }
        val removedArr = removed.toIntArray()
        fun disp(o: Int): Int {
            var n = 0
            for (r in removedArr) { if (r < o) n++ else break }
            return (o - n).coerceIn(0, b.length)
        }
        for (s in p.styles) {
            val span = spanStyle(s) ?: continue
            val (a, z) = disp(s.start) to disp(s.end)
            if (z > a) b.addStyle(span, a, z)
        }
        for (line in p.listLines) {
            val para = hangingIndent(line, p.text, style) ?: continue
            val (a, z) = disp(line.start) to disp(line.end)
            if (z > a) b.addStyle(para, a, z)
        }
        return Annotated(b.toAnnotatedString(), inline, removedArr)
    }
}

@Composable
fun rememberMarkdownPalette(): MarkdownPalette {
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    return remember(c, density, measurer) {
        MarkdownPalette(
            text = c.onSurface,
            link = c.primary,
            codeBackground = c.surfaceContainerHigh,
            muted = c.onSurfaceVariant,
            bullet = c.primary,
            density = density,
            measure = { prefix, style -> with(density) { measurer.measure(prefix, style).size.width.toSp() } },
        )
    }
}

/** Base text style for a block so the rendered and the editable version line up exactly. */
/** Vertical gap between two list items; must exceed the line spacing inside an item. */
val ListItemGap = 7.dp

fun blockTextStyle(kind: BlockKind, level: Int, palette: MarkdownPalette): TextStyle = when (kind) {
    BlockKind.HEADING -> EditorType.heading(level)
    BlockKind.LIST -> EditorType.list
    BlockKind.CODE, BlockKind.TABLE, BlockKind.HTML, BlockKind.MATH, BlockKind.FRONTMATTER -> EditorType.code
    else -> EditorType.body
}.copy(color = palette.text)
