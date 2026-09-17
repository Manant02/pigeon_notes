package ch.antoinette.pigeonnotes.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

enum class BlockKind { PARAGRAPH, HEADING, LIST, CODE, QUOTE, TABLE, HR, HTML, FRONTMATTER, IMAGE, MATH }

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
        val root = parser.buildMarkdownTreeFromString(doc)
        for (node in root.children) {
            if (node.startOffset < contentStart) continue
            val kind = kindOf(node, doc) ?: continue
            var end = node.endOffset
            while (end > node.startOffset && (doc[end - 1] == '\n' || doc[end - 1] == ' ')) end--
            if (end <= node.startOffset) continue
            blocks += Block(kind, node.startOffset, end, doc.substring(node.startOffset, end), headingLevel(node))
        }
        return blocks
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
        GFMElementTypes.ALERT -> BlockKind.QUOTE
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
