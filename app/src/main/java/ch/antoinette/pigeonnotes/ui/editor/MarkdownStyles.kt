package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyle
import ch.antoinette.pigeonnotes.domain.markdown.Projection
import ch.antoinette.pigeonnotes.domain.markdown.StyleRange
import ch.antoinette.pigeonnotes.ui.theme.EditorType

/** Theme colours resolved once per composition; maps abstract inline styles to SpanStyles. */
class MarkdownPalette(
    val text: Color,
    val link: Color,
    val codeBackground: Color,
    val muted: Color,
    val bullet: Color,
) {
    fun spanStyle(s: StyleRange): SpanStyle? = when (s.style) {
        InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        InlineStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough, color = muted)
        InlineStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, fontSize = 0.9.em())
        InlineStyle.MATH -> SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = link)
        InlineStyle.LINK -> SpanStyle(color = link, textDecoration = TextDecoration.Underline)
        InlineStyle.IMAGE_ALT -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
        InlineStyle.QUOTE -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
        InlineStyle.BULLET -> SpanStyle(color = bullet, fontWeight = FontWeight.Medium)
        InlineStyle.DIM -> SpanStyle(color = muted)
        InlineStyle.CHECKED -> SpanStyle(color = muted, textDecoration = TextDecoration.LineThrough)
        InlineStyle.HEADING -> null // block-level: applied through the base TextStyle
    }

    fun annotate(p: Projection): AnnotatedString {
        val b = AnnotatedString.Builder(p.text)
        for (s in p.styles) {
            val style = spanStyle(s) ?: continue
            b.addStyle(style, s.start.coerceIn(0, p.text.length), s.end.coerceIn(0, p.text.length))
        }
        return b.toAnnotatedString()
    }

    private fun Double.em() = (this * 17).sp
}

@Composable
fun rememberMarkdownPalette(): MarkdownPalette {
    val c = MaterialTheme.colorScheme
    return remember(c) {
        MarkdownPalette(
            text = c.onSurface,
            link = c.primary,
            codeBackground = c.surfaceContainerHigh,
            muted = c.onSurfaceVariant,
            bullet = c.primary,
        )
    }
}

/** Base text style for a block so the rendered and the editable version line up exactly. */
fun blockTextStyle(kind: BlockKind, level: Int, palette: MarkdownPalette): TextStyle = when (kind) {
    BlockKind.HEADING -> EditorType.heading(level)
    BlockKind.CODE, BlockKind.TABLE, BlockKind.HTML, BlockKind.MATH, BlockKind.FRONTMATTER -> EditorType.code
    else -> EditorType.body
}.copy(color = palette.text)
