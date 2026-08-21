package dev.caracal.core.export

import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * What an export is allowed to cost.
 *
 * Separate from `ResultLimits`, and much larger, because the two bound different
 * things. The grid's limits bound what is *retained and drawn* — a thousand rows,
 * because that is what a person can look at. An export retains nothing: each row
 * is written and forgotten, so the only reasons to stop are that the file has
 * become bigger than a file should be, that the query is producing rows faster
 * than anyone wanted, or that this has gone on long enough.
 *
 * [bytes] counts UTF-8 as written, and is checked before each row rather than
 * during one, so a file may pass it by at most the length of its last record.
 * [duration] is measured from the start of the whole export, connection and
 * planning included, because that is the wait the user is actually sitting through.
 */
data class ExportLimits(
    val rows: Long = 1_000_000,
    val bytes: Long = 256L * 1024 * 1024,
    val duration: Duration = 5.minutes,
)

/** Why an export stopped writing. Anything but [COMPLETE] means the file is a prefix. */
enum class ExportStop {
    COMPLETE,
    ROW_LIMIT,
    SIZE_LIMIT,
    TIME_LIMIT,
}

/**
 * What an export did.
 *
 * [rows] excludes the header. [bytes] is the size of the file when it was written
 * as UTF-8, which is what [CsvExport.writeToFile] writes.
 */
data class CsvExportReport(
    val rows: Long,
    val bytes: Long,
    val duration: Duration,
    val stopped: ExportStop = ExportStop.COMPLETE,
) {
    /** Whether the file holds the whole result. If not, [stopped] says what ended it. */
    val complete: Boolean get() = stopped == ExportStop.COMPLETE
}

/** File-level concerns of a CSV export: where it is written, and what it is called. */
object CsvExport {

    /**
     * Runs [body] against a UTF-8 writer onto [path], and leaves no file behind if
     * it fails.
     *
     * The encoding is fixed here rather than left to the caller because it is not a
     * preference: the values are PostgreSQL's, PostgreSQL's text is UTF-8, and a
     * platform-default writer would turn a Japanese column into question marks on a
     * Windows machine and nowhere else.
     *
     * A failed export deletes the file. A partial CSV is not a partial document —
     * it is a complete-looking one that happens to stop, with no way for anything
     * reading it to know that the rows simply ran out. Cancellation is a failure for
     * this purpose, and the delete runs under [NonCancellable] so that being
     * cancelled is not also how the file survives.
     */
    suspend fun <T> writeToFile(path: Path, body: suspend (Appendable) -> T): T {
        var finished = false
        try {
            // Buffered: the writer sees a field at a time, and a syscall per field is
            // the difference between an export that streams and one that crawls.
            val result = withContext(Dispatchers.IO) { Files.newBufferedWriter(path, Charsets.UTF_8) }
                .use { writer -> body(writer) }
            finished = true
            return result
        } finally {
            // After use{} closed it: Windows will not delete a file that is still open.
            if (!finished) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { Files.deleteIfExists(path) } }
            }
        }
    }

    /**
     * [suggestion] as a file name that every platform will accept.
     *
     * The name of an export is derived from things this application does not
     * control — a table name from a catalog, a tab title, a timestamp — and a file
     * system is a poor place to find out that one of them held a slash. What comes
     * out is a single name with an extension: never a path, never a device, never
     * something a shell or a `Finder` would read as more than a file.
     *
     * The rules are the union of three platforms' objections, applied to all of
     * them so that one machine's export can be opened on another.
     */
    fun fileName(suggestion: String): String {
        // A path is not a name. `../../etc/passwd` becomes `passwd`, which is a file
        // in the directory the user picked and not a file anywhere else.
        val base = suggestion.substringAfterLast('/').substringAfterLast('\\')
            .removeExtension()
            // Whitespace first, so that a tab or a newline in a name becomes the space
            // it reads as rather than an underscore the next rule would make of it.
            .replace(WHITESPACE, " ")
            .map { character -> if (character.isForbidden()) '_' else character }
            .joinToString("")
            // Windows drops trailing dots and spaces silently, so a name that ends in
            // one is a name that becomes a different file than the one reported here.
            .trim { it == '.' || it.isWhitespace() }
            .clipTo(MAX_BASE_LENGTH)

        if (base.isEmpty()) return "$FALLBACK.$EXTENSION"
        // `CON.csv` is still the console on Windows. The underscore is the cheapest
        // thing that is not.
        if (base.substringBefore('.').uppercase() in RESERVED_NAMES) return "_$base.$EXTENSION"
        return "$base.$EXTENSION"
    }

    /** `report.csv` and `report.CSV` are both `report`; `2026.08.20` keeps its dots. */
    private fun String.removeExtension(): String =
        if (endsWith(".$EXTENSION", ignoreCase = true)) dropLast(EXTENSION.length + 1) else this

    /**
     * Separators, the characters Windows reserves, the colon `Finder` still shows as
     * a slash, and every control character — including the NUL that ends a path for
     * the C library underneath every one of these platforms.
     */
    private fun Char.isForbidden(): Boolean = this in "\\/:*?\"<>|" || code < 0x20 || code == 0x7f

    /** Cut to [limit] characters without splitting one in two, as `ResultLimits.clip` does. */
    private fun String.clipTo(limit: Int): String {
        if (length <= limit) return this
        val end = if (this[limit - 1].isHighSurrogate()) limit - 1 else limit
        return substring(0, end).trimEnd { it == '.' || it.isWhitespace() }
    }

    private const val EXTENSION = "csv"

    private const val FALLBACK = "export"

    /** Long enough for a schema, a table, and a timestamp; short of every platform's cap. */
    private const val MAX_BASE_LENGTH = 80

    private val WHITESPACE = Regex("\\s+")

    private val RESERVED_NAMES = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (number in 1..9) {
            add("COM$number")
            add("LPT$number")
        }
    }
}
