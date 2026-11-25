package ch.antoinette.pigeonnotes.domain.markdown

/**
 * Raw HTML inside Markdown (`<u>text</u>`, `<br>`, `<!-- … -->`), rendered the way Obsidian and
 * Typora do: known tags are hidden and style the text between them, like `**` does. The source is
 * never changed; with the cursor inside an element its tags show again for editing.
 *
 * Only tag names of real HTML elements are touched. Anything else the Markdown parser reads as a
 * tag (`List<String>` in prose) stays visible, dimmed, so no text disappears from a note.
 */
internal object HtmlInline {

    /** One tag or comment, [start, end) in the block source. [group] ties tags that may pair (same inline parent). */
    class Tag(val start: Int, val end: Int, val group: Int, val name: String, val closing: Boolean, val selfClosing: Boolean, val attrs: String)

    private val TAG = Regex("""<(/?)([A-Za-z][A-Za-z0-9-]*)((?:\s+[^<>]*?)?)\s*(/?)>""")
    private val COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")

    /** Elements that style the text they enclose. */
    private val STYLES = mapOf(
        "b" to InlineStyle.BOLD, "strong" to InlineStyle.BOLD,
        "i" to InlineStyle.ITALIC, "em" to InlineStyle.ITALIC, "cite" to InlineStyle.ITALIC,
        "var" to InlineStyle.ITALIC, "dfn" to InlineStyle.ITALIC, "address" to InlineStyle.ITALIC,
        "u" to InlineStyle.UNDERLINE, "ins" to InlineStyle.UNDERLINE,
        "s" to InlineStyle.STRIKE, "del" to InlineStyle.STRIKE, "strike" to InlineStyle.STRIKE,
        "code" to InlineStyle.CODE, "kbd" to InlineStyle.CODE, "samp" to InlineStyle.CODE, "tt" to InlineStyle.CODE,
        "mark" to InlineStyle.HIGHLIGHT,
        "sub" to InlineStyle.SUBSCRIPT, "sup" to InlineStyle.SUPERSCRIPT,
        "small" to InlineStyle.SMALL,
        "a" to InlineStyle.LINK,
        "h1" to InlineStyle.BOLD, "h2" to InlineStyle.BOLD, "h3" to InlineStyle.BOLD,
        "h4" to InlineStyle.BOLD, "h5" to InlineStyle.BOLD, "h6" to InlineStyle.BOLD,
        "summary" to InlineStyle.BOLD, "dt" to InlineStyle.BOLD, "th" to InlineStyle.BOLD,
    )

    /** Elements without a closing tag. */
    private val VOID = setOf("br", "wbr", "img", "hr", "input", "meta", "link", "source", "col", "area", "embed", "track", "param", "base")

    /** Other real elements: their tags are hidden, their content shown plainly. */
    private val PLAIN = setOf(
        "span", "div", "p", "font", "center", "abbr", "q", "bdi", "bdo", "big", "data", "time", "label",
        "details", "section", "article", "aside", "header", "footer", "nav", "main", "figure", "figcaption",
        "blockquote", "ul", "ol", "li", "dl", "dd", "nobr", "ruby", "rt", "rp", "picture", "video", "audio",
        "table", "thead", "tbody", "tfoot", "tr", "td", "caption", "colgroup",
    )

    /**
     * Blocks that are shown as source in a code box rather than rendered: preformatted text,
     * scripts, tables (whose cells would run together) and the like.
     */
    private val RAW_BLOCK = Regex("""<(pre|script|style|textarea|table|svg|math|iframe|object|form|select|template)\b|<!(?!--)|<\?""", RegexOption.IGNORE_CASE)

    fun isKnown(name: String) = name in STYLES || name in VOID || name in PLAIN

    /** The tags in [from, to) of [src]: comments plus every `<name …>` / `</name>`. */
    fun scan(src: String, from: Int, to: Int, group: Int): List<Tag> {
        val out = ArrayList<Tag>()
        val text = src.substring(from, to)
        val comments = COMMENT.findAll(text).map { it.range }.toList()
        for (c in comments) out += Tag(from + c.first, from + c.last + 1, group, "!--", false, true, "")
        for (m in TAG.findAll(text)) {
            if (comments.any { m.range.first <= it.last && m.range.last >= it.first }) continue
            out += Tag(
                from + m.range.first, from + m.range.last + 1, group,
                name = m.groupValues[2].lowercase(),
                closing = m.groupValues[1] == "/",
                selfClosing = m.groupValues[4] == "/",
                attrs = m.groupValues[3],
            )
        }
        out.sortBy { it.start }
        return out
    }

    /** Whether an HTML block is simple enough to render as text instead of showing its source. */
    fun rendersAsText(source: String): Boolean {
        if (RAW_BLOCK.containsMatchIn(source)) return false
        val tags = scan(source, 0, source.length, 0)
        if (tags.any { it.name != "!--" && !isKnown(it.name) }) return false
        // Something must remain visible, or the block could not be seen (or tapped) at all.
        val sb = StringBuilder()
        var pos = 0
        for (t in tags) {
            if (t.start < pos) continue
            sb.append(source, pos, t.start); pos = t.end
        }
        sb.append(source, pos, source.length)
        return sb.isNotBlank()
    }

    /**
     * Turns [tags] (in source order) into markers, styles and links. Opening and closing tags of
     * the same element pair up within a [Tag.group]; the pair owns everything between them, so the
     * tags show while the cursor is anywhere in the element, as with `**bold**`.
     */
    fun apply(
        src: String,
        tags: List<Tag>,
        styles: MutableList<StyleRange>,
        markers: MutableList<Marker>,
        links: MutableList<LinkRange>,
    ) {
        val open = ArrayList<Tag>()
        for (t in tags) {
            if (t.name == "!--") {
                hide(src, t, t.start, t.end, markers)
                continue
            }
            if (!isKnown(t.name)) {
                styles += StyleRange(t.start, t.end, InlineStyle.DIM)
                continue
            }
            styles += StyleRange(t.start, t.end, InlineStyle.DIM) // how the tag looks while it shows
            when {
                t.name in VOID || t.selfClosing -> single(src, t, markers, styles, links)
                !t.closing -> open += t
                else -> {
                    val i = open.indexOfLast { it.name == t.name && it.group == t.group }
                    if (i < 0) {
                        hide(src, t, t.start, t.end, markers)
                    } else {
                        // Elements left open inside this one end here, as in HTML.
                        for (inner in open.subList(i + 1, open.size)) pair(src, inner, null, t.start, styles, markers, links)
                        pair(src, open[i], t, t.start, styles, markers, links)
                        while (open.size > i) open.removeAt(open.lastIndex)
                    }
                }
            }
        }
        // An element never closed in its paragraph: only its tag is hidden, the text stays plain.
        for (t in open) hide(src, t, t.start, t.end, markers)
        markers.sortBy { it.start }
    }

    /**
     * Hides [t]. A tag alone on its line (`<div>`, `</details>`) takes the line break with it, so a
     * block wrapped in tags shows no empty lines above and below its text.
     */
    private fun hide(src: String, t: Tag, ownerStart: Int, ownerEnd: Int, markers: MutableList<Marker>, replacement: String = "") {
        var start = t.start
        var end = t.end
        if (replacement.isEmpty()) {
            var ls = start
            while (ls > 0 && (src[ls - 1] == ' ' || src[ls - 1] == '\t')) ls--
            var le = end
            while (le < src.length && (src[le] == ' ' || src[le] == '\t')) le++
            val aloneOnLine = (ls == 0 || src[ls - 1] == '\n') && (le == src.length || src[le] == '\n')
            if (aloneOnLine) {
                if (le < src.length) {
                    start = ls; end = le + 1
                } else if (ls > 0 && markers.none { it.end >= ls - 1 && it.start < ls }) {
                    start = ls - 1; end = le
                }
            }
        }
        markers += Marker(start, end, minOf(ownerStart, start), maxOf(ownerEnd, end), replacement)
    }

    private fun pair(
        src: String,
        open: Tag,
        close: Tag?,
        contentEnd: Int,
        styles: MutableList<StyleRange>,
        markers: MutableList<Marker>,
        links: MutableList<LinkRange>,
    ) {
        val ownerEnd = close?.end ?: contentEnd
        hide(src, open, open.start, ownerEnd, markers)
        if (close != null) hide(src, close, open.start, ownerEnd, markers)
        val style = STYLES[open.name] ?: return
        if (contentEnd > open.end) styles += StyleRange(open.end, contentEnd, style)
        if (open.name == "a") {
            val href = attrs(open.attrs)["href"]
            if (!href.isNullOrBlank()) links += LinkRange(open.start, ownerEnd, href)
        }
    }

    private fun single(
        src: String,
        t: Tag,
        markers: MutableList<Marker>,
        styles: MutableList<StyleRange>,
        links: MutableList<LinkRange>,
    ) {
        when (t.name) {
            // A line break; at the end of a line the source's own newline already breaks it.
            "br" -> {
                var after = t.end
                while (after < src.length && (src[after] == ' ' || src[after] == '\t')) after++
                val atLineEnd = after >= src.length || src[after] == '\n'
                if (atLineEnd) markers += Marker(t.start, t.end, t.start, t.end) else markers += Marker(t.start, t.end, t.start, t.end, "\n")
            }
            "img" -> {
                val a = attrs(t.attrs)
                val alt = a["alt"].orEmpty().replace('\n', ' ')
                markers += Marker(t.start, t.end, t.start, t.end, replacement = if (alt.isBlank()) "🖼" else "🖼 $alt")
                a["src"]?.takeIf { it.isNotBlank() }?.let { links += LinkRange(t.start, t.end, it) }
            }
            "hr" -> markers += Marker(t.start, t.end, t.start, t.end, replacement = "———")
            else -> hide(src, t, t.start, t.end, markers)
        }
    }

    fun attrs(raw: String): Map<String, String> =
        ATTR.findAll(raw).associate { m ->
            m.groupValues[1].lowercase() to (m.groups[2]?.value ?: m.groups[3]?.value ?: m.groupValues[4])
        }
}
