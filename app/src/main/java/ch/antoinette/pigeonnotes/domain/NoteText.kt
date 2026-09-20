package ch.antoinette.pigeonnotes.domain

/** Small helpers for deriving list metadata from a note's path and text. */
object NoteText {
    fun title(path: String): String = path.substringAfterLast('/').removeSuffix(".md").removeSuffix(".MD")

    /** Quick notes get a generated `note-yyMMdd-HHmm` name; the file name is never shown for them. */
    fun isQuickNoteName(path: String): Boolean = QUICK_NAME.matches(title(path))

    /**
     * The list index for a note: its display title and a preview. Ordinary notes are titled by their
     * file name; a quick note by its first line, with the preview taken from what follows.
     */
    fun index(path: String, text: String): Pair<String, String> {
        if (!isQuickNoteName(path)) return title(path) to preview(text)
        val lines = contentLines(text).toList()
        val first = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("![") && it != "---" }
            ?: return title(path) to ""
        val title = clean(first.trimStart('#', ' '), 80)
        val rest = lines.dropWhile { it != first }.drop(1).joinToString("\n")
        return title to preview(rest)
    }

    /** First meaningful line: skips front matter, headings, blank lines and image-only lines. */
    fun preview(text: String, maxChars: Int = 140): String {
        val line = contentLines(text)
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("![") && it != "---" }
            ?: return ""
        return clean(line, maxChars)
    }

    private fun contentLines(text: String): Sequence<String> {
        var lines = text.lineSequence()
        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            if (end > 0) lines = text.substring(end + 4).lineSequence()
        }
        return lines.map { it.trim() }
    }

    private fun clean(line: String, maxChars: Int): String {
        val cleaned = line
            .replace(Regex("""[*_`~]+"""), "")
            .replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")
            .replace(Regex("""^\s*([-*+]|\d+[.)])\s+"""), "")   // bullet
            .replace(Regex("""^\[[ xX]\]\s*"""), "")           // task marker
            .removePrefix("> ")
            .trim()
        return if (cleaned.length > maxChars) cleaned.take(maxChars - 1) + "…" else cleaned
    }

    private val QUICK_NAME = Regex("""note-\d{6}-\d{4}( \d+)?""")

    /** Filenames must be safe for Nextcloud and Android; keeps unicode, drops path separators and control chars. */
    fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("""[/\\:*?"<>|\p{Cntrl}]"""), " ")
            .trim()
            .trimEnd('.')
            .take(120)
            .ifEmpty { "Untitled" }
}
