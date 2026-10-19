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
     * A visible blank line: one that is not the [Block.separator] right after a block. Blank lines
     * before the first block are all visible. Typing into one turns it into a block.
     */
    EMPTY,
}

/** Identity of a [Block] while a note is being edited; [NONE] for blocks straight from the parser. */
@JvmInline
value class BlockId(val value: Long) {
    companion object {
        val NONE = BlockId(0)
    }
}

/**
 * A top-level unit of a note: a run of whole lines that the markdown parser reads as one block, or
 * ([BlockKind.EMPTY]) one blank line. Every line of the note belongs to exactly one block, so
 * [BlockParser.join] gives back the note exactly.
 */
data class Block(
    val kind: BlockKind,
    /** The block's lines, verbatim, joined by "\n". For [BlockKind.EMPTY] the blank line itself ("" or whitespace). */
    val source: String,
    /** Heading level for [BlockKind.HEADING], else 0. */
    val level: Int = 0,
    /**
     * The blank line right after a block that separates it from what follows ("" or that line's
     * whitespace), or null when the next line follows directly (a heading right above its text).
     * It belongs to the block, so it is not shown as an empty line of its own.
     */
    val separator: String? = null,
    val id: BlockId = BlockId.NONE,
) {
    /** How many lines of the note the block takes up, its separator included. */
    val lineCount: Int get() = source.count { it == '\n' } + 1 + (if (separator != null) 1 else 0)

    /** Same text and meaning, whatever the [id]. */
    fun sameAs(other: Block) =
        kind == other.kind && source == other.source && level == other.level && separator == other.separator
}

/** Splits a note into whole-line blocks, and joins them back. */
object BlockParser {
    val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)

    /**
     * The blocks of [doc]. A block always consists of whole lines (indentation and trailing spaces
     * included); one blank line after a block is its separator, and every further blank line is an
     * [BlockKind.EMPTY] block. `join(parse(doc), doc.endsWith("\n")) == doc` for every text.
     */
    fun parse(doc: String): List<Block> {
        val lines = lines(doc)
        val ranges = lineRanges(doc, lines)
        val out = ArrayList<Block>()
        var i = 0
        var r = 0
        while (i < lines.size) {
            val range = ranges.getOrNull(r)
            if (range == null || range.first > i) {
                out += Block(BlockKind.EMPTY, lines[i])
                i++
                continue
            }
            var next = range.last + 1
            val separator = lines.getOrNull(next)?.takeIf { it.isBlank() && ranges.getOrNull(r + 1)?.first != next }
            if (separator != null) next++
            out += Block(range.kind, lines.subList(range.first, range.last + 1).joinToString("\n"), range.level, separator)
            i = next
            r++
        }
        return out
    }

    /** The note text of [blocks], the inverse of [parse]. [finalNewline]: whether the last line ends with a newline. */
    fun join(blocks: List<Block>, finalNewline: Boolean = true): String {
        if (blocks.isEmpty()) return ""
        val sb = StringBuilder()
        var first = true
        fun line(text: String) {
            if (!first) sb.append('\n')
            sb.append(text)
            first = false
        }
        for (b in blocks) {
            line(b.source)
            b.separator?.let(::line)
        }
        if (finalNewline) sb.append('\n')
        return sb.toString()
    }

    /** The lines of [doc], without their newlines; a final newline does not start another line. */
    fun lines(doc: String): List<String> = if (doc.isEmpty()) emptyList() else doc.removeSuffix("\n").split('\n')

    private class LineRange(val first: Int, val last: Int, val kind: BlockKind, val level: Int)

    /**
     * The lines [first, last] each block of [doc] covers. Top-level markdown blocks never share a
     * line; should two ranges overlap anyway they are merged, and a non-blank line no block claims
     * becomes a paragraph, so no text can end up hidden in an "empty" line.
     */
    private fun lineRanges(doc: String, lines: List<String>): List<LineRange> {
        if (lines.isEmpty()) return emptyList()
        val lineStarts = IntArray(lines.size)
        for (k in 1 until lines.size) lineStarts[k] = lineStarts[k - 1] + lines[k - 1].length + 1
        fun lineOf(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }

        val claimed = ArrayList<LineRange>()
        for (raw in rawBlocks(doc)) {
            val first = lineOf(raw.start)
            val last = lineOf(maxOf(raw.start, raw.end - 1)).coerceAtLeast(first)
            val prev = claimed.lastOrNull()
            if (prev != null && first <= prev.last) {
                claimed[claimed.lastIndex] = LineRange(prev.first, maxOf(prev.last, last), prev.kind, prev.level)
            } else {
                claimed += LineRange(first, last, raw.kind, raw.level)
            }
        }

        val out = ArrayList<LineRange>()
        var i = 0
        fun unclaimedUpTo(end: Int) {
            while (i < end) {
                if (lines[i].isBlank()) { i++; continue }
                val start = i
                while (i + 1 < end && lines[i + 1].isNotBlank()) i++
                out += LineRange(start, i, BlockKind.PARAGRAPH, 0)
                i++
            }
        }
        for (range in claimed) {
            unclaimedUpTo(range.first)
            out += range
            i = range.last + 1
        }
        unclaimedUpTo(lines.size)
        return out
    }

    /** Front matter and the markdown blocks in document order, as character ranges. */
    private fun rawBlocks(doc: String): List<Raw> {
        val out = ArrayList<Raw>()
        var contentStart = 0
        frontMatterEnd(doc)?.let { end ->
            out += Raw(BlockKind.FRONTMATTER, 0, end, 0)
            contentStart = end
        }
        out += contentBlocks(doc, contentStart)
        return out
    }

    private class Raw(val kind: BlockKind, val start: Int, val end: Int, val level: Int)

    /**
     * Blocks in document order. Display math (`$$` on a line of its own up to the next line ending in
     * `$$`, blank lines allowed inside) is cut out first, because the markdown parser does not know
     * it and would split it into paragraphs; the text between formulas is parsed as usual.
     */
    private fun contentBlocks(doc: String, from: Int): List<Raw> {
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
            while (end > node.startOffset && text[end - 1].isWhitespace()) end--
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
