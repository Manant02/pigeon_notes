package ch.antoinette.pigeonnotes.domain

import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.BlockParser
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small helpers for deriving list metadata and file names from a note's path and text. */
object NoteText {
    /** The title a new note starts with, selected in the editor so typing replaces it. */
    const val NEW_NOTE_TITLE = "New Note"
    const val SHARED_NOTE_TITLE = "Shared Note"

    /** The whole text of a new note: a note still exactly like this when the editor closes is dropped. */
    const val NEW_NOTE_TEXT = "# $NEW_NOTE_TITLE\n"

    /** The file name without `.md`. */
    fun title(path: String): String = path.substringAfterLast('/').removeSuffix(".md").removeSuffix(".MD")

    /** Quick notes get a generated `note-yyMMdd-HHmm[_Title]` name. */
    fun isQuickNoteName(path: String): Boolean = STAMPED.matches(title(path))

    /**
     * The list index for a note: its display title and a preview. The title is the note's first H1;
     * without one, a `note-yyMMdd-HHmm_Title` file shows its title part, a bare `note-yyMMdd-HHmm`
     * file its first line (or, when empty, the moment it was made) and any other file its name.
     */
    fun index(path: String, text: String): Pair<String, String> {
        heading(text)?.let { return clean(it, 80) to preview(text) }
        val stem = title(path)
        val stamped = STAMPED.matchEntire(stem) ?: return stem to preview(text)
        val slug = stamped.groupValues[2]
        if (slug.isNotEmpty()) return slug.replace('-', ' ') to preview(text)
        val lines = contentLines(text).toList()
        val first = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("![") && it != "---" }
            ?: return stampTitle(stamped.groupValues[1]) to ""
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

    /**
     * The text of the note's first level-1 heading (`# Title` or `Title` underlined with `===`),
     * markup removed; null if it has none. Headings in front matter or code blocks don't count.
     */
    fun heading(text: String): String? {
        val block = BlockParser.parse(text).firstOrNull { it.kind == BlockKind.HEADING && it.level == 1 } ?: return null
        val lines = block.source.lines().map { it.trim() }
        val raw = if (lines.first().startsWith("#")) {
            lines.first().trimStart('#').trim().replace(CLOSING_HASHES, "")
        } else {
            lines.dropLast(1).joinToString(" ") // setext: the underline is the last line
        }
        return inline(raw).trim().ifEmpty { null }
    }

    /**
     * [title] as a file name part: runs of spaces and punctuation become one dash, apostrophes go,
     * letters (umlauts included), digits and case stay. At most [SLUG_MAX] characters, cut at a dash
     * where possible. Empty when nothing usable is left.
     */
    fun slug(title: String): String {
        val s = title.replace(APOSTROPHES, "").replace(NON_WORD, "-").trim('-')
        if (s.length <= SLUG_MAX) return s
        val dash = s.lastIndexOf('-', SLUG_MAX) // a dash right after the limit keeps the last word whole
        return (if (dash >= SLUG_MAX / 2) s.take(dash) else s.take(SLUG_MAX)).trimEnd('-')
    }

    /** The file stem for a new note in [folder] titled [title]: see [nameForTitle]. */
    fun newStem(folder: String, title: String?, now: LocalDateTime): String {
        val slug = title?.let(::slug).orEmpty()
        if (isPageFolder(folder)) return slug.ifEmpty { "Untitled" }
        val stamp = "note-" + now.format(STAMP)
        return if (slug.isEmpty()) stamp else "${stamp}_$slug"
    }

    /**
     * The file stem the note's H1 asks for, or null when the name already fits (or must stay).
     * A `note-yyMMdd-HHmm` name keeps its stamp and carries the title after an underscore, wherever
     * the note is, except inside a page folder. Standalone pages directly in `Pages/` are named by
     * the title alone. Everything else, conflict copies and notes without an H1 keep their name.
     * Names that only differ in spaces versus dashes, punctuation or a ` 2` suffix count as
     * fitting, so desktop-made names aren't touched.
     *
     * Notes inside a page folder (`Pages/<Folder>/…`, at any depth) always keep their name: those
     * folders are curated by hand, often on the desktop, and their file names are part of that.
     * The one exception is a note the app has just created there ([fresh]), whose placeholder name
     * (`New-Note.md`) is replaced by its title once, like a standalone page's.
     */
    fun nameForTitle(path: String, text: String, fresh: Boolean = false): String? {
        val heading = heading(text) ?: return null
        val stem = title(path)
        if ("(conflict " in stem) return null
        val slug = slug(heading)
        val folder = path.substringBeforeLast('/', "")
        val inPageFolder = isInPageFolder(folder)
        if (inPageFolder && !fresh) return null
        val stamped = STAMPED.matchEntire(stem)
        if (stamped != null && !inPageFolder) {
            if (stamped.groupValues[2] == slug) return null
            val base = "note-" + stamped.groupValues[1]
            return if (slug.isEmpty()) base else "${base}_$slug"
        }
        if (!isPageFolder(folder) || slug.isEmpty()) return null
        return if (slug(stem.replace(UNIQUE_SUFFIX, "")) == slug) null else slug
    }

    /** True for a folder inside `Pages/` (a page folder or one nested in it), not `Pages/` itself. */
    fun isInPageFolder(folder: String) = folder.startsWith(NotesRepository.PAGES_DIR + "/")

    private fun isPageFolder(folder: String) =
        folder == NotesRepository.PAGES_DIR || folder.startsWith(NotesRepository.PAGES_DIR + "/")

    /** `261008-1328` as "Thu 08.10.26, 13:28". */
    private fun stampTitle(stamp: String): String =
        runCatching { LocalDateTime.parse(stamp, STAMP).format(STAMP_TITLE) }.getOrDefault("note-$stamp")

    private fun contentLines(text: String): Sequence<String> {
        var lines = text.lineSequence()
        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            if (end > 0) lines = text.substring(end + 4).lineSequence()
        }
        return lines.map { it.trim() }
    }

    /** Emphasis markers and link targets removed. */
    private fun inline(text: String): String = text
        .replace(Regex("""[*_`~]+"""), "")
        .replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")

    private fun clean(line: String, maxChars: Int): String {
        val cleaned = inline(line)
            .replace(Regex("""^\s*([-*+]|\d+[.)])\s+"""), "")   // bullet
            .replace(Regex("""^\[[ xX]\]\s*"""), "")           // task marker
            .removePrefix("> ")
            .trim()
        return if (cleaned.length > maxChars) cleaned.take(maxChars - 1) + "…" else cleaned
    }

    /** `note-yyMMdd-HHmm`, an optional `_Title` (a [slug], so no spaces) and a uniqueness suffix. */
    private val STAMPED = Regex("""note-(\d{6}-\d{4})(?:_([^ ]*))?( \d+)?""")
    private val UNIQUE_SUFFIX = Regex(""" \d+$""")
    private val CLOSING_HASHES = Regex("""\s+#+$""")
    private val APOSTROPHES = Regex("""['’]""")
    private val NON_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""")
    private const val SLUG_MAX = 50
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd-HHmm")
    private val STAMP_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd.MM.yy, HH:mm", Locale.ENGLISH)

    /** Filenames must be safe for Nextcloud and Android; keeps unicode, drops path separators and control chars. */
    fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("""[/\\:*?"<>|\p{Cntrl}]"""), " ")
            .trim()
            .trimEnd('.')
            .take(120)
            .ifEmpty { "Untitled" }
}
