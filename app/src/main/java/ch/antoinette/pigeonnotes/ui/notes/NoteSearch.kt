package ch.antoinette.pigeonnotes.ui.notes

import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.domain.NoteText
import ch.antoinette.pigeonnotes.domain.NotesRepository
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** A note found by [NoteSearch], with what to highlight. */
data class SearchHit(
    val row: NoteRow,
    /** Where the note lives, e.g. "Quick note", "Recipes › Asian", "#Work", "Archive". */
    val location: String,
    /** Ranges of [NoteRow.title] that match a search word. */
    val titleHits: List<IntRange>,
    /** The body line around the first match in the body, or the preview when only the title matches. */
    val snippet: String,
    val snippetHits: List<IntRange>,
    val archived: Boolean,
)

/**
 * Full-text search over notes. Pure logic, kept free of Android so it can be unit-tested.
 *
 * A note matches when every word of the query appears in its title, its file name or its text
 * (as plain text: Markdown markers and link targets don't count). Case and accents are ignored,
 * so "cafe" finds "Café". Notes matching in the title come first; otherwise the given order
 * (newest first) is kept.
 */
object NoteSearch {
    /** How much of a matching line is shown, centred on the match. */
    private const val SNIPPET_CHARS = 140

    fun terms(query: String): List<String> =
        query.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.map(::fold).distinct()

    /**
     * Notes in [notes] (with their text from [textOf]) that match every word of [query], title
     * matches first. Empty for a blank query.
     */
    fun search(notes: List<SyncEntry>, query: String, textOf: (SyncEntry) -> String): List<SearchHit> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        return notes.mapNotNull { match(it, textOf(it), terms) }
            .sortedByDescending { hit -> terms.count { t -> fold(hit.row.title).contains(t) } }
    }

    fun match(note: SyncEntry, text: String, terms: List<String>): SearchHit? {
        val title = fold(note.title)
        val fileName = fold(NoteText.title(note.path))
        val lines = plainLines(text, note.title)
        val foldedLines = lines.map(::fold)
        val unmatched = terms.filterNot { t -> title.contains(t) || fileName.contains(t) || foldedLines.any { it.contains(t) } }
        if (unmatched.isNotEmpty()) return null

        // The line holding the most words, earliest first: the best single line to show.
        val best = foldedLines.indices.maxByOrNull { i -> terms.count { foldedLines[i].contains(it) } * 10_000 - i }
            ?.takeIf { i -> terms.any { foldedLines[i].contains(it) } }
        val (snippet, snippetHits) = if (best == null) {
            note.preview to ranges(fold(note.preview), terms)
        } else {
            window(lines[best], foldedLines[best], terms)
        }
        val row = note.toRow()
        return SearchHit(
            row = row,
            location = location(note.path),
            titleHits = ranges(title, terms),
            snippet = snippet,
            snippetHits = snippetHits,
            archived = isArchived(note.path),
        )
    }

    /**
     * Where a note lives, as shown above a search result: "Quick note" for the root, "Page" for a
     * single page, the folders below `Pages/` for a page folder, "#Tag" or "Untagged" under
     * `Tags/`, "Archive", or the folder path for anything else.
     */
    fun location(path: String): String {
        val parent = if ('/' in path) path.substringBeforeLast('/') else ""
        val pages = NotesRepository.PAGES_DIR
        val tags = NotesRepository.TAGS_DIR
        return when {
            parent.isEmpty() -> "Quick note"
            isArchived(path) -> "Archive"
            parent == pages -> "Page"
            parent.startsWith("$pages/") -> parent.removePrefix("$pages/").replace("/", " › ")
            parent == tags -> "Untagged"
            parent.startsWith("$tags/") -> "#" + parent.removePrefix("$tags/")
            else -> parent.replace("/", " › ")
        }
    }

    fun isArchived(path: String) = path.startsWith(NotesRepository.ARCHIVE_DIR + "/")

    /**
     * Lowercase, accents removed, one character for one so that positions in the folded text are
     * positions in the original ("Café" → "cafe").
     */
    fun fold(text: String): String = buildString(text.length) {
        for (c in text) {
            if (c.code < 128) { append(c.lowercaseChar()); continue }
            val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
            append(base.lowercaseChar())
        }
    }

    /**
     * The note's text as lines of plain text: front matter, the title heading, blank lines, image
     * lines, code fences and table rules dropped; Markdown markers and link targets removed.
     */
    fun plainLines(text: String, title: String): List<String> {
        var body = text
        if (body.startsWith("---")) {
            val end = body.indexOf("\n---", 3)
            if (end > 0) body = body.substring(end + 4)
        }
        var titleSkipped = false
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("![") && !TABLE_RULE.matches(it) && it != "---" }
            .map(::plain)
            .filter { line ->
                // The title is matched on its own; its heading line would only repeat it.
                if (!titleSkipped && line == title) { titleSkipped = true; false } else line.isNotEmpty()
            }
            .toList()
    }

    private fun plain(line: String): String = line
        .replace(LINK, "$1")
        .replace(WIKI_LINK) { it.groupValues[3].ifEmpty { it.groupValues[1] } }
        .replace(BLOCK_MARKER, "")
        .replace(TASK, "")
        .replace(EMPHASIS, "")
        .replace('|', ' ')
        .replace(SPACES, " ")
        .trim()

    /** Up to [SNIPPET_CHARS] of [line] around its first match, with ellipses where it was cut. */
    private fun window(line: String, folded: String, terms: List<String>): Pair<String, List<IntRange>> {
        if (line.length <= SNIPPET_CHARS) return line to ranges(folded, terms)
        val first = terms.mapNotNull { t -> folded.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: 0
        // Some words before the match for context, then as much as fits.
        var start = (first - 30).coerceAtLeast(0)
        if (start > 0) start = line.indexOf(' ', start).let { if (it in start until first) it + 1 else start }
        val end = (start + SNIPPET_CHARS).coerceAtMost(line.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < line.length) "…" else ""
        val shown = prefix + line.substring(start, end) + suffix
        val hits = ranges(folded.substring(start, end), terms).map { (it.first + prefix.length)..(it.last + prefix.length) }
        return shown to hits
    }

    /** Every occurrence of every term in [folded], merged where they overlap. */
    fun ranges(folded: String, terms: List<String>): List<IntRange> {
        val found = terms.flatMap { t ->
            generateSequence(folded.indexOf(t).takeIf { it >= 0 }) { prev ->
                folded.indexOf(t, prev + t.length).takeIf { it >= 0 }
            }.map { it until it + t.length }.toList()
        }.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in found) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            else merged += r
        }
        return merged
    }

    private val TABLE_RULE = Regex("""\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?""")
    private val LINK = Regex("""!?\[([^\]]*)\]\([^)]*\)""")
    private val WIKI_LINK = Regex("""\[\[([^\]|]*)(\|([^\]]*))?\]\]""")
    private val BLOCK_MARKER = Regex("""^(#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+)+""")
    private val TASK = Regex("""^\[[ xX]\]\s*""")
    private val EMPHASIS = Regex("""\*\*|__|~~|==|`""")
    private val SPACES = Regex("""\s+""")
}

/**
 * The text of each note, read from disk once and kept until the note changes (its modification
 * time), so typing a search does not read every file again for each letter.
 */
class NoteTextCache(private val read: suspend (String) -> String) {
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    suspend fun texts(notes: List<SyncEntry>): Map<String, String> {
        val live = notes.mapTo(HashSet()) { it.path }
        cache.keys.retainAll(live)
        return notes.associate { note ->
            val cached = cache[note.path]
            val text = if (cached != null && cached.first == note.modified) cached.second
            else read(note.path).also { cache[note.path] = note.modified to it }
            note.path to text
        }
    }
}
