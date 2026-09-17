package ch.antoinette.pigeonnotes.domain.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** Abstract styling instructions; the UI maps them to theme-aware SpanStyles. Keeps this layer testable. */
enum class InlineStyle { BOLD, ITALIC, CODE, STRIKE, LINK, IMAGE_ALT, MATH, HEADING, QUOTE, BULLET, DIM, SUBSCRIPT, SUPERSCRIPT, HIGHLIGHT }

data class StyleRange(val start: Int, val end: Int, val style: InlineStyle, val level: Int = 0)

/**
 * A run of markdown syntax that is hidden (or swapped for [replacement]) while the cursor is outside
 * its owner element [ownerStart, ownerEnd]. Offsets are into the block source.
 */
data class Marker(val start: Int, val end: Int, val ownerStart: Int, val ownerEnd: Int, val replacement: String = "") {
    fun ownerContains(cursor: Int) = cursor in ownerStart..ownerEnd
}

data class LinkRange(val start: Int, val end: Int, val url: String)

/** An inline `$…$` element; [latex] is the formula without delimiters. */
data class MathRange(val start: Int, val end: Int, val latex: String)

/** One list-item line: [contentStart] is where the text after bullet/checkbox begins, for hanging indents. */
data class ListLine(val start: Int, val end: Int, val contentStart: Int)

/** A `[ ]` / `[x]` token. */
data class CheckboxRange(val start: Int, val end: Int, val checked: Boolean)

class Analysis(
    val source: String,
    val styles: List<StyleRange>,
    val markers: List<Marker>,
    val links: List<LinkRange>,
    val math: List<MathRange> = emptyList(),
    val listLines: List<ListLine> = emptyList(),
    val checkboxes: List<CheckboxRange> = emptyList(),
)

/** Where an element ended up in the projected text (output offsets). */
data class MathHit(val start: Int, val end: Int, val latex: String)
data class CheckboxHit(val start: Int, val end: Int, val sourceStart: Int, val checked: Boolean)

/** Result of applying a set of markers: the visible text plus shifted styles and offset mapping. */
class Projection(
    val text: String,
    val styles: List<StyleRange>,
    val links: List<LinkRange>,
    private val applied: List<Marker>,
    val math: List<MathHit> = emptyList(),
    val listLines: List<ListLine> = emptyList(),
    val checkboxes: List<CheckboxHit> = emptyList(),
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
    /** Placeholder character standing in for a rendered formula in the projected text. */
    const val MATH_PLACEHOLDER = '￼'

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
        val w = Walker(source, styles, markers, links)
        w.walk(root)
        scanExtras(source, w.protectedSpans, styles, markers)
        markers.sortBy { it.start }
        return Analysis(source, styles, markers, links, w.math, w.listLines, w.checkboxes)
    }

    /**
     * Applies every marker whose owner does not contain [cursor] (all of them when [cursor] is null).
     * With [renderMath], inline formulas outside the cursor collapse to [MATH_PLACEHOLDER] so the UI
     * can draw them; the active text field cannot show images, so it leaves this off.
     */
    fun project(a: Analysis, cursor: Int? = null, renderMath: Boolean = false): Projection {
        var markers = a.markers
        val mathHits = ArrayList<Pair<Marker, String>>()
        if (renderMath && a.math.isNotEmpty()) {
            val collapsed = a.math.filter { cursor == null || cursor !in it.start..it.end }
            markers = markers.filter { m -> collapsed.none { m.start >= it.start && m.end <= it.end } }
            for (r in collapsed) {
                val m = Marker(r.start, r.end, r.start, r.end, MATH_PLACEHOLDER.toString())
                markers = markers + m
                mathHits += m to r.latex
            }
            markers = markers.sortedBy { it.start }
        }
        val applied = markers.filter { cursor == null || !it.ownerContains(cursor) }
        val sb = StringBuilder(a.source.length)
        var pos = 0
        for (m in applied) {
            if (m.start < pos) continue // overlapping markers are not expected; ignore defensively
            sb.append(a.source, pos, m.start).append(m.replacement)
            pos = m.end
        }
        sb.append(a.source, pos, a.source.length)

        fun range(s: Int, e: Int): Pair<Int, Int>? {
            val o1 = Projection.mapToOutput(applied, s)
            val o2 = Projection.mapToOutput(applied, e)
            return if (o2 > o1) o1 to o2 else null
        }
        val styles = a.styles.mapNotNull { s -> range(s.start, s.end)?.let { (o1, o2) -> s.copy(start = o1, end = o2) } }
        val links = a.links.mapNotNull { l -> range(l.start, l.end)?.let { (o1, o2) -> l.copy(start = o1, end = o2) } }
        val math = mathHits.mapNotNull { (m, latex) ->
            if (m !in applied) null else Projection.mapToOutput(applied, m.start).let { o -> MathHit(o, o + 1, latex) }
        }
        val listLines = a.listLines.mapNotNull { l ->
            val s = Projection.mapToOutput(applied, l.start)
            val e = Projection.mapToOutput(applied, l.end)
            val c = Projection.mapToOutput(applied, l.contentStart)
            if (e > s) ListLine(s, e, c.coerceIn(s, e)) else null
        }
        val checkboxes = a.checkboxes.mapNotNull { c ->
            val m = applied.firstOrNull { it.start == c.start } ?: return@mapNotNull null
            val o = Projection.mapToOutput(applied, m.start)
            CheckboxHit(o, o + m.replacement.length, c.start, c.checked)
        }
        return Projection(sb.toString(), styles, links, applied, math, listLines, checkboxes)
    }

    // Typora-style inline syntax the CommonMark/GFM parser does not know about. Scanned over the
    // plain text only: anything inside code, math, URLs or existing markers is left alone.
    private val HIGHLIGHT = Regex("""==([^=\n]+)==""")
    private val SUPERSCRIPT = Regex("""\^([^\s^]+)\^""")
    private val SUBSCRIPT = Regex("""(?<!~)~([^\s~]+)~(?!~)""") // a single tilde, never part of ~~strike~~

    private fun scanExtras(
        src: String,
        protectedSpans: List<IntRange>,
        styles: MutableList<StyleRange>,
        markers: MutableList<Marker>,
    ) {
        fun overlapsProtected(s: Int, e: Int) = protectedSpans.any { s < it.last + 1 && e > it.first }
        fun apply(re: Regex, style: InlineStyle, markLength: Int) {
            for (m in re.findAll(src)) {
                val s = m.range.first
                val e = m.range.last + 1
                if (overlapsProtected(s, e)) continue
                if (markers.any { it.start < e && it.end > s }) continue // real syntax already claims it
                markers += Marker(s, s + markLength, s, e)
                markers += Marker(e - markLength, e, s, e)
                styles += StyleRange(s + markLength, e - markLength, style)
            }
        }
        apply(HIGHLIGHT, InlineStyle.HIGHLIGHT, 2)
        apply(SUPERSCRIPT, InlineStyle.SUPERSCRIPT, 1)
        apply(SUBSCRIPT, InlineStyle.SUBSCRIPT, 1)
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
        val math = ArrayList<MathRange>()
        val listLines = ArrayList<ListLine>()
        val checkboxes = ArrayList<CheckboxRange>()

        /** Ranges where `~`, `^` and `=` are literal: code, math, URLs, raw HTML. */
        val protectedSpans = ArrayList<IntRange>()

        private fun protect(node: ASTNode) {
            protectedSpans += node.startOffset until node.endOffset
        }

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

                t == GFMElementTypes.INLINE_MATH -> {
                    protect(node)
                    val inner = delimited(node, GFMTokenTypes.DOLLAR, InlineStyle.MATH)
                    if (inner != null) math += MathRange(node.startOffset, node.endOffset, src.substring(inner.first, inner.second))
                }

                t == MarkdownElementTypes.CODE_SPAN -> {
                    protect(node)
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
                    protect(node)
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
                    protect(node)
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.LINK)
                    links += LinkRange(node.startOffset, node.endOffset, src.substring(node.startOffset, node.endOffset))
                }

                t == MarkdownElementTypes.LIST_ITEM -> listItem(node)

                t == MarkdownElementTypes.BLOCK_QUOTE -> {
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.QUOTE)
                    children(node)
                }

                // The alert's colour and icon are drawn around the block; the body stays plain.
                t == GFMElementTypes.ALERT -> {
                    // The quote marker opening the body line arrives as whitespace, not as a quote token.
                    for (c in node.children) {
                        if (c.type == MarkdownTokenTypes.WHITE_SPACE && src.substring(c.startOffset, c.endOffset).contains('>')) {
                            markers += Marker(c.startOffset, c.endOffset, 0, src.length)
                        }
                    }
                    children(node)
                }

                t == GFMTokenTypes.ALERT_TITLE -> {
                    var hideEnd = node.endOffset
                    while (hideEnd < src.length && src[hideEnd] != '\n') hideEnd++
                    if (hideEnd < src.length) hideEnd++ // the line break too
                    markers += Marker(node.startOffset, hideEnd, 0, src.length)
                }

                t == MarkdownTokenTypes.BLOCK_QUOTE -> {
                    var hideEnd = node.endOffset
                    while (hideEnd < src.length && src[hideEnd] == ' ') hideEnd++
                    markers += Marker(node.startOffset, hideEnd, 0, src.length)
                }

                t == MarkdownTokenTypes.HTML_TAG -> {
                    protect(node)
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.DIM)
                }

                t == MarkdownElementTypes.CODE_BLOCK || t == MarkdownElementTypes.CODE_FENCE -> {
                    protect(node)
                    styles += StyleRange(node.startOffset, node.endOffset, InlineStyle.CODE)
                }

                t == GFMTokenTypes.CHECK_BOX -> Unit // handled by listItem

                else -> children(node)
            }
        }

        private fun children(node: ASTNode) = node.children.forEach { walk(it) }

        /** Returns the inner (content) range when delimiters were found. */
        private fun delimited(node: ASTNode, delimiter: IElementType, style: InlineStyle): Pair<Int, Int>? {
            val kids = node.children
            // `**` arrives as two one-char EMPH tokens, so take the whole leading/trailing run.
            val lead = kids.takeWhile { it.type == delimiter }
            val trail = kids.takeLastWhile { it.type == delimiter }
            return if (lead.isNotEmpty() && trail.isNotEmpty() && lead.size + trail.size < kids.size) {
                val openEnd = lead.last().endOffset
                val closeStart = trail.first().startOffset
                markers += Marker(node.startOffset, openEnd, node.startOffset, node.endOffset)
                markers += Marker(closeStart, node.endOffset, node.startOffset, node.endOffset)
                styles += StyleRange(openEnd, closeStart, style)
                kids.subList(lead.size, kids.size - trail.size).forEach { walk(it) }
                openEnd to closeStart
            } else {
                styles += StyleRange(node.startOffset, node.endOffset, style)
                children(node)
                null
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
            dest?.let { protect(it) }
            val url = dest?.let { src.substring(it.startOffset, it.endOffset).trim('<', '>') } ?: ""
            links += LinkRange(node.startOffset, node.endOffset, url)
            text.children.forEach { if (it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET) walk(it) }
        }

        private fun listItem(node: ASTNode) {
            val bullet = node.children.firstOrNull { it.type == MarkdownTokenTypes.LIST_BULLET || it.type == MarkdownTokenTypes.LIST_NUMBER }
            if (bullet != null) {
                val lineEnd = src.indexOf('\n', bullet.endOffset).let { if (it < 0) src.length else it }
                val lineStart = src.lastIndexOf('\n', bullet.startOffset - 1) + 1
                var afterBullet = bullet.endOffset
                while (afterBullet < src.length && src[afterBullet] == ' ') afterBullet++
                val checkbox = findCheckbox(node, afterBullet, lineEnd)
                var contentStart = afterBullet
                if (checkbox != null) {
                    var afterBox = checkbox.endOffset
                    while (afterBox < src.length && src[afterBox] == ' ') afterBox++
                    val checked = src.substring(checkbox.startOffset, checkbox.endOffset).contains('x', ignoreCase = true)
                    // Task items show only the box, no bullet.
                    markers += Marker(bullet.startOffset, afterBullet, lineStart, lineEnd)
                    markers += Marker(checkbox.startOffset, afterBox, lineStart, lineEnd, replacement = if (checked) "☑ " else "☐ ")
                    checkboxes += CheckboxRange(checkbox.startOffset, checkbox.endOffset, checked)
                    contentStart = afterBox
                } else if (bullet.type == MarkdownTokenTypes.LIST_BULLET) {
                    markers += Marker(bullet.startOffset, afterBullet, lineStart, lineEnd, replacement = "•  ")
                } else {
                    val raw = src.substring(bullet.startOffset, bullet.endOffset).trim()
                    styles += StyleRange(bullet.startOffset, bullet.startOffset + raw.length, InlineStyle.BULLET)
                }
                listLines += ListLine(lineStart, lineEnd, contentStart)
            }
            children(node)
        }

        private fun findCheckbox(node: ASTNode, from: Int, to: Int): ASTNode? {
            if (node.type == GFMTokenTypes.CHECK_BOX) return node.takeIf { it.startOffset in from..to }
            for (c in node.children) {
                if (c.startOffset > to) break
                findCheckbox(c, from, to)?.let { return it }
            }
            return null
        }

        private fun atxLevel(t: IElementType): Int = when (t) {
            MarkdownElementTypes.ATX_1 -> 1; MarkdownElementTypes.ATX_2 -> 2; MarkdownElementTypes.ATX_3 -> 3
            MarkdownElementTypes.ATX_4 -> 4; MarkdownElementTypes.ATX_5 -> 5; else -> 6
        }
    }
}
