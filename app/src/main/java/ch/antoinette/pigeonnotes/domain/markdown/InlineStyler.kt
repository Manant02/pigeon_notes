package ch.antoinette.pigeonnotes.domain.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** Abstract styling instructions; the UI maps them to theme-aware SpanStyles. Keeps this layer testable. */
enum class InlineStyle { BOLD, ITALIC, CODE, STRIKE, LINK, IMAGE_ALT, MATH, HEADING, QUOTE, BULLET, DIM, CHECKED }

data class StyleRange(val start: Int, val end: Int, val style: InlineStyle, val level: Int = 0)

/**
 * A run of markdown syntax that is hidden (or swapped for [replacement]) while the cursor is outside
 * its owner element [ownerStart, ownerEnd]. Offsets are into the block source.
 */
data class Marker(val start: Int, val end: Int, val ownerStart: Int, val ownerEnd: Int, val replacement: String = "") {
    fun ownerContains(cursor: Int) = cursor in ownerStart..ownerEnd
}

data class LinkRange(val start: Int, val end: Int, val url: String)

class Analysis(
    val source: String,
    val styles: List<StyleRange>,
    val markers: List<Marker>,
    val links: List<LinkRange>,
)

/** Result of applying a set of markers: the visible text plus shifted styles and offset mapping. */
class Projection(
    val text: String,
    val styles: List<StyleRange>,
    val links: List<LinkRange>,
    private val applied: List<Marker>,
) {
    fun toOutput(sourceOffset: Int): Int = mapToOutput(applied, sourceOffset)
    fun toSource(outputOffset: Int): Int = mapToSource(applied, outputOffset)

    companion object {
        fun mapToOutput(applied: List<Marker>, x: Int): Int {
            var delta = 0
            for (m in applied) {
                if (m.end <= x) delta += m.replacement.length - (m.end - m.start)
                else if (m.start < x) return m.start + delta + m.replacement.length // inside a marker → after replacement
                else break
            }
            return x + delta
        }

        fun mapToSource(applied: List<Marker>, y: Int): Int {
            var delta = 0 // output - source so far
            for (m in applied) {
                val outStart = m.start + delta
                val outEnd = outStart + m.replacement.length
                if (outEnd <= y) delta += m.replacement.length - (m.end - m.start)
                else if (outStart < y) return m.end // inside replacement → after the marker
                else break
            }
            return y - delta
        }
    }
}

object InlineStyler {

    fun analyze(source: String, kind: BlockKind, level: Int = 0): Analysis {
        val styles = ArrayList<StyleRange>()
        val markers = ArrayList<Marker>()
        val links = ArrayList<LinkRange>()
        if (kind == BlockKind.CODE || kind == BlockKind.TABLE || kind == BlockKind.HTML || kind == BlockKind.MATH || kind == BlockKind.FRONTMATTER) {
            styles += StyleRange(0, source.length, InlineStyle.CODE)
            if (kind == BlockKind.CODE) fenceMarkers(source, markers)
            return Analysis(source, styles, markers, links)
        }
        val root = BlockParser.parseTree(source)
        Walker(source, styles, markers, links).walk(root)
        markers.sortBy { it.start }
        return Analysis(source, styles, markers, links)
    }

    /** Applies every marker whose owner does not contain [cursor] (all of them when [cursor] is null). */
    fun project(a: Analysis, cursor: Int? = null): Projection {
        val applied = a.markers.filter { cursor == null || !it.ownerContains(cursor) }
        val sb = StringBuilder(a.source.length)
        var pos = 0
        for (m in applied) {
            if (m.start < pos) continue // overlapping markers are not expected; ignore defensively
            sb.append(a.source, pos, m.start).append(m.replacement)
            pos = m.end
        }
        sb.append(a.source, pos, a.source.length)
        val styles = a.styles.mapNotNull { s ->
            val o1 = Projection.mapToOutput(applied, s.start)
            val o2 = Projection.mapToOutput(applied, s.end)
            if (o2 > o1) s.copy(start = o1, end = o2) else null
        }
        val links = a.links.mapNotNull { l ->
            val o1 = Projection.mapToOutput(applied, l.start)
            val o2 = Projection.mapToOutput(applied, l.end)
            if (o2 > o1) l.copy(start = o1, end = o2) else null
        }
        return Projection(sb.toString(), styles, links, applied)
    }

    private fun fenceMarkers(source: String, markers: MutableList<Marker>) {
        val firstEol = source.indexOf('\n')
        if (!source.startsWith("```") && !source.startsWith("~~~")) return
        val whole = 0..source.length
        if (firstEol < 0) {
            markers += Marker(0, source.length, whole.first, whole.last); return
        }
        markers += Marker(0, firstEol + 1, whole.first, whole.last)
        val lastEol = source.lastIndexOf('\n')
        if (lastEol > firstEol && source.substring(lastEol + 1).trim().let { it.startsWith("```") || it.startsWith("~~~") }) {
            markers += Marker(lastEol, source.length, whole.first, whole.last)
        }
    }

    private class Walker(
        val src: String,
        val styles: MutableList<StyleRange>,
        val markers: MutableList<Marker>,
        val links: MutableList<LinkRange>,
    ) {
        fun walk(node: ASTNode) {
            val t = node.type
            when {
                t == MarkdownElementTypes.ATX_1 || t == MarkdownElementTypes.ATX_2 || t == MarkdownElementTypes.ATX_3 ||
                    t == MarkdownElementTypes.ATX_4 || t == MarkdownElementTypes.ATX_5 || t == MarkdownElementTypes.ATX_6 -> {
                    val header = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_HEADER }
                    if (header != null) {
                        var hideEnd = header.endOffset
                        while (hideEnd < node.endOffset && src[hideEnd] == ' ') hideEnd++
                        markers += Marker(node.startOffset, hideEnd, node.startOffset, node.endOffset)
                    }
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.HEADING, level = atxLevel(t))
                    children(node)
                }

                t == MarkdownElementTypes.SETEXT_1 || t == MarkdownElementTypes.SETEXT_2 -> {
                    val underline = node.children.lastOrNull { it.type == MarkdownTokenTypes.SETEXT_1 || it.type == MarkdownTokenTypes.SETEXT_2 }
                    if (underline != null) {
                        var hideStart = underline.startOffset
                        while (hideStart > node.startOffset && (src[hideStart - 1] == '\n' || src[hideStart - 1] == ' ')) hideStart--
                        markers += Marker(hideStart, node.endOffset, node.startOffset, node.endOffset)
                    }
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.HEADING, level = if (t == MarkdownElementTypes.SETEXT_1) 1 else 2)
                    children(node)
                }

                t == MarkdownElementTypes.STRONG -> delimited(node, MarkdownTokenTypes.EMPH, InlineStyle.BOLD)
                t == MarkdownElementTypes.EMPH -> delimited(node, MarkdownTokenTypes.EMPH, InlineStyle.ITALIC)
                t == GFMElementTypes.STRIKETHROUGH -> delimited(node, GFMTokenTypes.TILDE, InlineStyle.STRIKE)
                t == GFMElementTypes.INLINE_MATH -> delimited(node, GFMTokenTypes.DOLLAR, InlineStyle.MATH)

                t == MarkdownElementTypes.CODE_SPAN -> {
                    val ticks = node.children.filter { it.type == MarkdownTokenTypes.BACKTICK }
                    val open = ticks.firstOrNull(); val close = ticks.lastOrNull()
                    if (open != null && close != null && open !== close) {
                        markers += Marker(open.startOffset, open.endOffset, node.startOffset, node.endOffset)
                        markers += Marker(close.startOffset, close.endOffset, node.startOffset, node.endOffset)
                        styles += StyleRange(open.endOffset, close.startOffset, InlineStyle.CODE)
                    } else styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.CODE)
                }

                t == MarkdownElementTypes.INLINE_LINK -> inlineLink(node, node.startOffset, node.endOffset)

                t == MarkdownElementTypes.IMAGE -> {
                    val link = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
                    val text = link?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    if (link != null && text != null) {
                        // Rendered as "🖼 alt"; the whole image syntax shows while the cursor is inside it.
                        markers += Marker(node.startOffset, text.startOffset + 1, node.startOffset, node.endOffset, replacement = "🖼 ")
                        markers += Marker(text.endOffset - 1, node.endOffset, node.startOffset, node.endOffset)
                        styles += StyleRange(text.startOffset + 1, text.endOffset - 1, InlineStyle.IMAGE_ALT)
                        link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.let {
                            links += LinkRange(node.startOffset, node.endOffset, src.substring(it.startOffset, it.endOffset).trim('<', '>'))
                        }
                    } else styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.DIM)
                }

                t == MarkdownElementTypes.AUTOLINK -> {
                    val lt = node.children.firstOrNull { it.type == MarkdownTokenTypes.LT }
                    val gt = node.children.lastOrNull { it.type == MarkdownTokenTypes.GT }
                    if (lt != null && gt != null) {
                        markers += Marker(lt.startOffset, lt.endOffset, node.startOffset, node.endOffset)
                        markers += Marker(gt.startOffset, gt.endOffset, node.startOffset, node.endOffset)
                    }
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.LINK)
                    links += LinkRange(node.startOffset, node.endOffset, src.substring(node.startOffset, node.endOffset).trim('<', '>'))
                }

                t == GFMTokenTypes.GFM_AUTOLINK -> {
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.LINK)
                    links += LinkRange(node.startOffset, node.endOffset, src.substring(node.startOffset, node.endOffset))
                }

                t == MarkdownElementTypes.LIST_ITEM -> listItem(node)

                t == MarkdownElementTypes.BLOCK_QUOTE || t == GFMElementTypes.ALERT -> {
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.QUOTE)
                    children(node)
                }

                t == MarkdownTokenTypes.BLOCK_QUOTE -> {
                    var hideEnd = node.endOffset
                    while (hideEnd < src.length && src[hideEnd] == ' ') hideEnd++
                    markers += Marker(node.startOffset, hideEnd, 0, src.length)
                }

                t == MarkdownTokenTypes.HTML_TAG -> styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.DIM)

                t == MarkdownElementTypes.CODE_BLOCK || t == MarkdownElementTypes.CODE_FENCE ->
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.CODE)

                t == GFMTokenTypes.CHECK_BOX -> {
                    val checked = src.substring(node.startOffset, node.endOffset).contains('x', ignoreCase = true)
                    val lineEnd = src.indexOf('\n', node.endOffset).let { if (it < 0) src.length else it }
                    val lineStart = src.lastIndexOf('\n', node.startOffset - 1) + 1
                    var hideEnd = node.endOffset
                    while (hideEnd < src.length && src[hideEnd] == ' ') hideEnd++
                    markers += Marker(node.startOffset, hideEnd, lineStart, lineEnd, replacement = if (checked) "☑ " else "☐ ")
                    if (checked) styles += StyleRange(hideEnd, lineEnd, InlineStyle.CHECKED)
                }

                else -> children(node)
            }
        }

        private fun children(node: ASTNode) = node.children.forEach { walk(it) }

        private fun delimited(node: ASTNode, delimiter: IElementType, style: InlineStyle) {
            val kids = node.children
            // `**` arrives as two one-char EMPH tokens, so take the whole leading/trailing run.
            val lead = kids.takeWhile { it.type == delimiter }
            val trail = kids.takeLastWhile { it.type == delimiter }
            if (lead.isNotEmpty() && trail.isNotEmpty() && lead.size + trail.size < kids.size) {
                val openEnd = lead.last().endOffset
                val closeStart = trail.first().startOffset
                markers += Marker(node.startOffset, openEnd, node.startOffset, node.endOffset)
                markers += Marker(closeStart, node.endOffset, node.startOffset, node.endOffset)
                styles += StyleRange(openEnd, closeStart, style)
                kids.subList(lead.size, kids.size - trail.size).forEach { walk(it) }
            } else {
                styles += StyleRange(node.startOffset, node.endOffset, style)
                children(node)
            }
        }

        private fun inlineLink(node: ASTNode, ownerStart: Int, ownerEnd: Int) {
            val text = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            val dest = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
            if (text == null) {
                styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.LINK); return
            }
            markers += Marker(node.startOffset, text.startOffset + 1, ownerStart, ownerEnd)
            markers += Marker(text.endOffset - 1, node.endOffset, ownerStart, ownerEnd)
            styles += StyleRange(text.startOffset + 1, text.endOffset - 1, InlineStyle.LINK)
            val url = dest?.let { src.substring(it.startOffset, it.endOffset).trim('<', '>') } ?: ""
            links += LinkRange(node.startOffset, node.endOffset, url)
            text.children.forEach { if (it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET) walk(it) }
        }

        private fun listItem(node: ASTNode) {
            val bullet = node.children.firstOrNull { it.type == MarkdownTokenTypes.LIST_BULLET || it.type == MarkdownTokenTypes.LIST_NUMBER }
            if (bullet != null) {
                val lineEnd = src.indexOf('\n', bullet.endOffset).let { if (it < 0) src.length else it }
                val lineStart = src.lastIndexOf('\n', bullet.startOffset - 1) + 1
                var end = bullet.endOffset
                while (end < src.length && src[end] == ' ') end++
                val raw = src.substring(bullet.startOffset, bullet.endOffset).trim()
                if (bullet.type == MarkdownTokenTypes.LIST_BULLET) {
                    markers += Marker(bullet.startOffset, end, lineStart, lineEnd, replacement = "•  ")
                } else {
                    styles += StyleRange(bullet.startOffset, bullet.startOffset + raw.length, InlineStyle.BULLET)
                }
            }
            children(node)
        }

        private fun atxLevel(t: IElementType): Int = when (t) {
            MarkdownElementTypes.ATX_1 -> 1; MarkdownElementTypes.ATX_2 -> 2; MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4; MarkdownElementTypes.ATX_5 -> 5; else -> 6
        }
    }
}
