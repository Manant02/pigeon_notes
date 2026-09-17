package ch.antoinette.pigeonnotes.domain

/** Small helpers for deriving list metadata from a note's path and text. */
object NoteText {
    fun title(path: String): String = path.substringAfterLast('/').removeSuffix(".md").removeSuffix(".MD")

    /** First meaningful line: skips front matter, headings, blank lines and image-only lines. */
    fun preview(text: String, maxChars: Int = 140): String {
        var lines = text.lineSequence()
        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            if (end > 0) lines = text.substring(end + 4).lineSequence()
        }
        val line = lines
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("![") && it != "---" }
            ?: return ""
        val cleaned = line
            .replace(Regex("""[*_`~]+"""), "")
            .replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")
            .removePrefix("- ").removePrefix("* ").removePrefix("> ")
        return if (cleaned.length > maxChars) cleaned.take(maxChars - 1) + "…" else cleaned
    }

    /** Filenames must be safe for Nextcloud and Android; keeps unicode, drops path separators and control chars. */
    fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("""[/\\:*?"<>|\p{Cntrl}]"""), " ")
            .trim()
            .trimEnd('.')
            .take(120)
            .ifEmpty { "Untitled" }
}
