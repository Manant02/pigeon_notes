package ch.antoinette.pigeonnotes.domain.markdown

/** One heading of a note, for the table of contents. [index] is the heading's position in the block list. */
data class TocEntry(val index: Int, val id: BlockId, val level: Int, val text: String)

/** The table of contents of a note: its headings, as plain text without the markdown around them. */
object Toc {
    fun of(blocks: List<Block>): List<TocEntry> = blocks.mapIndexedNotNull { i, b ->
        if (b.kind != BlockKind.HEADING) return@mapIndexedNotNull null
        TocEntry(i, b.id, b.level.coerceAtLeast(1), headingText(b.source))
    }

    /** `## Title ##` → `Title`; a setext heading (text over `===` or `---`) → its text lines. */
    fun headingText(source: String): String {
        val lines = source.lines()
        val raw = if (ATX.containsMatchIn(lines.first())) {
            lines.first().trim().replace(ATX, "").replace(ATX_CLOSE, "")
        } else {
            lines.dropLast(1).joinToString(" ") { it.trim() }
        }
        return raw
            .replace(Regex("""!?\[([^\]]*)\]\([^)]*\)"""), "$1")
            .replace(Regex("""\[\[([^\]|]*\|)?([^\]]*)\]\]"""), "$2")
            .replace(Regex("""(\*\*|__|~~|==|`)"""), "")
            .replace(Regex("""(?<![\w*])[*_](?=\S)|(?<=\S)[*_](?![\w*])"""), "")
            .trim()
    }

    private val ATX = Regex("""^#{1,6}(\s+|$)""")
    private val ATX_CLOSE = Regex("""\s+#+\s*$|^#+\s*$""")
}
