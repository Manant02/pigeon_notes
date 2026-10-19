package ch.antoinette.pigeonnotes.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

enum class BlockKind {
    PARAGRAPH, HEADING, LIST, CODE, QUOTE, TABLE, HR, HTML, FRONTMATTER, IMAGE, MATH, ALERT,

    /**
     * A blank line beyond the single one that separates blocks (or before the first block). It has a
     * zero-length range at the start of that line; typing into it splices `text + "\n"` there.
     */
    EMPTY,
}

/** A top-level editing unit of the document: [start, end) in the full text, never including trailing newlines. */
data class Block(
    val kind: BlockKind,
    val start: Int,
    val end: Int,
    val source: String,
    /** Heading level for [BlockKind.HEADING], else 0. */
    val level: Int = 0,
)

/** Splits a document into top-level blocks with exact source ranges, so an edit to one block can be spliced back. */
object BlockParser {
    val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)

    fun parse(doc: String): List<Block> {
        val blocks = ArrayList<Block>()
        var contentStart = 0
        frontMatterEnd(doc)?.let { end ->
            blocks += Block(BlockKind.FRONTMATTER, 0, end, doc.substring(0, end))
            contentStart = end
        }
        // After front matter the gap is measured from its closing newline, like after any other block.
        var prevEnd = if (contentStart > 0) contentStart - 1 else 0
        var first = contentStart == 0
        for (raw in rawBlocks(doc, contentStart)) {
            blocks += emptyLines(doc, prevEnd, raw.start, leading = first)
            blocks += Block(raw.kind, raw.start, raw.end, doc.substring(raw.start, raw.end), raw.level)
            prevEnd = raw.end
            first = false
        }
        // Blank lines after the last block are just as visible as the ones between blocks.
        blocks += emptyLines(doc, prevEnd, doc.length, leading = first)
        return blocks
    }

    private class Raw(val kind: BlockKind, val start: Int, val end: Int, val level: Int)

    /**
     * Blocks in document order. Display math (`$$` on a line of its own up to the next line ending in
     * `$$`, blank lines allowed inside) is cut out first, because the markdown parser does not know
     * it and would split it into paragraphs; the text between formulas is parsed as usual.
     */
    private fun rawBlocks(doc: String, from: Int): List<Raw> {
        val out = ArrayList<Raw>()
        var segmentStart = from
        for ((start, end) in mathBlocks(doc, from)) {
            out += parseSegment(doc, segmentStart, start)
            out += Raw(BlockKind.MATH, start, end, 0)
            segmentStart = end
        }
        out += parseSegment(doc, segmentStart, doc.length)
        return out
    }

    private fun parseSegment(doc: String, from: Int, to: Int): List<Raw> {
        if (from >= to) return emptyList()
        val text = doc.substring(from, to)
        val root = parser.buildMarkdownTreeFromString(text)
        val out = ArrayList<Raw>()
        for (node in root.children) {
            val kind = kindOf(node, text) ?: continue
            var end = node.endOffset
            while (end > node.startOffset && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
            if (end <= node.startOffset) continue
            out += Raw(kind, from + node.startOffset, from + end, headingLevel(node))
        }
        return out
    }

    /** [start, end) ranges of `$$` display-math blocks starting at a line of their own, end excluding the newline. */
    fun mathBlocks(doc: String, from: Int = 0): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = from
        var fence: String? = null // inside a ``` / ~~~ code block, `$$` is just text
        while (i < doc.length) {
            val lineEnd = doc.indexOf('\n', i).let { if (it < 0) doc.length else it }
            val line = doc.substring(i, lineEnd)
            val trimmed = line.trim()
            val fenceHere = FENCE.find(trimmed)?.value
            if (fence != null) {
                if (fenceHere != null && fenceHere[0] == fence[0] && fenceHere.length >= fence.length) fence = null
            } else if (fenceHere != null) {
                fence = fenceHere
            } else if (trimmed.startsWith("$$")) {
                val closeInLine = trimmed.length >= 4 && trimmed.endsWith("$$")
                if (closeInLine) {
                    out += i to lineEnd
                } else {
                    val close = closingMathLine(doc, lineEnd + 1)
                    if (close != null) {
                        out += i to close
                        i = close + 1
                        continue
                    }
                }
            }
            i = lineEnd + 1
        }
        return out
    }

    private val FENCE = Regex("""^(`{3,}|~{3,})""")

    /**
     * The fence that would close a ``` / ~~~ code block left open at the end of [text], or null when
     * every fence is closed. An unclosed fence runs to the end of the note and swallows everything
     * below it, so the editor closes one before writing it back.
     */
    fun unclosedFence(text: String): String? {
        var open: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            val f = FENCE.find(t)?.value ?: continue
            if (open == null) {
                // A backtick fence's info string cannot contain backticks: "```x```" is inline code.
                if (f[0] == '`' && t.substring(f.length).contains('`')) continue
                open = f
            } else if (f[0] == open[0] && f.length >= open.length && t.all { it == open[0] }) {
                open = null
            }
        }
        return open
    }

    /** Whether [source] is a fenced code block with its closing fence on the last line. */
    fun isClosedFence(source: String): Boolean =
        FENCE.containsMatchIn(source.trimStart()) && source.trimEnd().contains('\n') && unclosedFence(source) == null

    /** End offset of the first line at or after [from] whose text ends with `$$`, or null if the block never closes. */
    private fun closingMathLine(doc: String, from: Int): Int? {
        var i = from
        while (i < doc.length) {
            val lineEnd = doc.indexOf('\n', i).let { if (it < 0) doc.length else it }
            if (doc.substring(i, lineEnd).trimEnd().endsWith("$$")) return lineEnd
            i = lineEnd + 1
        }
        return null
    }

    /**
     * EMPTY blocks for the blank lines in the gap [from, to). One newline ends the previous block and
     * one more is the normal separator; every further newline is an empty line the user can see and edit.
     */
    private fun emptyLines(doc: String, from: Int, to: Int, leading: Boolean): List<Block> {
        val newlines = (from until to).filter { doc[it] == '\n' }
        val skip = if (leading) 0 else 2
        if (newlines.size <= skip) return emptyList()
        // Each extra newline terminates an empty line that starts at that same offset.
        return newlines.drop(skip).map { nl -> Block(BlockKind.EMPTY, nl, nl, "") }
    }

    /** Full parse of a single block's source, for inline styling. */
    fun parseTree(source: String): ASTNode = parser.buildMarkdownTreeFromString(source)

    private fun kindOf(node: ASTNode, doc: String): BlockKind? = when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> if (isImageOnly(node, doc)) BlockKind.IMAGE else BlockKind.PARAGRAPH
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 -> BlockKind.HEADING
        MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> BlockKind.LIST
        MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> BlockKind.CODE
        MarkdownElementTypes.BLOCK_QUOTE -> BlockKind.QUOTE
        MarkdownElementTypes.HTML_BLOCK -> BlockKind.HTML
        MarkdownElementTypes.LINK_DEFINITION -> BlockKind.PARAGRAPH
        MarkdownTokenTypes.HORIZONTAL_RULE -> BlockKind.HR
        GFMElementTypes.TABLE -> BlockKind.TABLE
        GFMElementTypes.BLOCK_MATH -> BlockKind.MATH
        GFMElementTypes.ALERT -> BlockKind.ALERT
        MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE -> null
        else -> BlockKind.PARAGRAPH
    }

    private fun headingLevel(node: ASTNode): Int = when (node.type) {
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> 1
        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> 2
        MarkdownElementTypes.ATX_3 -> 3
        MarkdownElementTypes.ATX_4 -> 4
        MarkdownElementTypes.ATX_5 -> 5
        MarkdownElementTypes.ATX_6 -> 6
        else -> 0
    }

    private fun isImageOnly(paragraph: ASTNode, doc: String): Boolean {
        val meaningful = paragraph.children.filter {
            it.type != MarkdownTokenTypes.WHITE_SPACE && it.type != MarkdownTokenTypes.EOL
        }
        return meaningful.size == 1 && meaningful[0].type == MarkdownElementTypes.IMAGE
    }

    /** End offset (exclusive, past the closing line's newline) of a YAML front matter block, if the doc starts with one. */
    fun frontMatterEnd(doc: String): Int? {
        if (!doc.startsWith("---\n") && !doc.startsWith("---\r\n")) return null
        var i = doc.indexOf('\n') + 1
        while (i < doc.length) {
            val lineEnd = doc.indexOf('\n', i).let { if (it < 0) doc.length else it }
            val line = doc.substring(i, lineEnd).trimEnd('\r')
            if (line == "---" || line == "...") return minOf(lineEnd + 1, doc.length)
            i = lineEnd + 1
        }
        return null
    }
}
