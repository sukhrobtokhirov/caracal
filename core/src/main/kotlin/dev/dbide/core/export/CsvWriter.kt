package dev.dbide.core.export

import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column

/** RFC 4180's separator, and the one every consumer assumes when the file says `.csv`. */
private const val DELIMITER = ','

/**
 * A lone `\.` on a line ends a `COPY` stream. Quoted, it is just two characters.
 */
private const val COPY_TERMINATOR = "\\."

/**
 * How a CSV file spells the things a database has and a spreadsheet does not.
 *
 * There is one option, and it is the one with no defensible default. An empty
 * field is what an empty string looks like, so a NULL written as an empty field
 * cannot be told from `''` — the exact distinction the grid goes to trouble to
 * draw. [nullText] is written unquoted and any value that happens to equal it is
 * written quoted, so `NULL` and `"NULL"` are different things in the file and a
 * reader that understands quoting can tell which was which.
 *
 * A delimiter, a line ending, and a byte-order mark are deliberately not options.
 * The record separator is CRLF because RFC 4180 says so and every reader accepts
 * it. There is no BOM: it is not part of UTF-8, and it arrives as three stray
 * characters at the front of the first column name in everything that does not
 * special-case it — Excel's convenience against everyone else's corruption.
 */
data class CsvOptions(val nullText: String = "NULL") {
    init {
        require(nullText.none { it == DELIMITER || it == '"' || it == '\n' || it == '\r' }) {
            "A null representation is written unquoted, so it cannot contain a delimiter, a quote, or a line break."
        }
    }
}

/**
 * Writes rows to [out] as CSV, one record at a time.
 *
 * Streaming is the whole design. [out] is a `Writer` onto a file for a real
 * export and a `StringBuilder` in a test, and neither this class nor the caller
 * ever holds the finished document: an export bounded at a few hundred megabytes
 * that had to be assembled in memory first would be an export that kills the
 * window on the way to disk.
 *
 * [bytes] is what has been written measured in UTF-8, counted while each field is
 * scanned for characters needing quotes rather than in a second pass. It is the
 * exact size of the file when [out] encodes as UTF-8, which is what
 * [CsvExport.writeToFile] guarantees, and it is what an export's byte budget is
 * spent against — a limit counted in Kotlin's UTF-16 characters would be off by up
 * to four times on the text most likely to be large.
 */
class CsvWriter(private val out: Appendable, private val options: CsvOptions = CsvOptions()) {

    var bytes: Long = 0L
        private set

    /**
     * The header row.
     *
     * Duplicate names are written as they are. `SELECT 1 AS a, 2 AS a` is legal
     * SQL, and renaming one of them here would be inventing a column name that the
     * query never produced.
     */
    fun header(columns: List<Column>) {
        columns.forEachIndexed { index, column ->
            if (index > 0) emit(DELIMITER)
            writeField(column.name)
        }
        endRecord()
    }

    fun row(cells: List<CellValue>) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) emit(DELIMITER)
            // The one field written without inspection: the sentinel is validated to
            // need no quoting, and quoting it would make it a value.
            if (cell is CellValue.Null) emit(options.nullText) else writeField(textOf(cell))
        }
        endRecord()
    }

    /**
     * A cell as the exact text the file should carry.
     *
     * A preview is refused rather than written. A truncated value reaching here
     * means an export was fed the grid's display copy instead of a fresh read, and
     * a CSV whose cells are silently prefixes of the real ones is worse than no
     * CSV — it looks complete, and nothing downstream can tell that it is not.
     */
    private fun textOf(cell: CellValue): String = when (cell) {
        CellValue.Null -> options.nullText
        is CellValue.Text -> {
            require(!cell.truncated) { "A truncated value cannot be exported; re-run the query with export limits." }
            cell.value
        }
        is CellValue.Integer -> cell.value.toString()
        // Plain notation, never `1E-10`: the value is identical either way, and which
        // of the two a spreadsheet understands is up to the spreadsheet.
        is CellValue.Decimal -> cell.value.toPlainString()
        // PostgreSQL's own text form is `t`/`f`, which it also accepts back; `true`
        // and `false` are accepted back too and are what everything else reads.
        is CellValue.Bool -> if (cell.value) "true" else "false"
        is CellValue.Binary -> {
            require(!cell.truncated) { "A truncated value cannot be exported; re-run the query with export limits." }
            cell.preview
        }
    }

    private fun writeField(text: String) {
        if (!needsQuotes(text)) {
            emit(text)
            return
        }
        emit('"')
        for (character in text) {
            if (character == '"') emit('"')
            emit(character)
        }
        emit('"')
    }

    /**
     * Whether [text] can be written bare.
     *
     * The first three reasons are the format's: a delimiter, a quote, or a line
     * break inside a bare field ends the field, the value, or the record. The rest
     * are about not losing something on the way back in — leading and trailing
     * spaces that a lenient parser would trim, a value that would otherwise read as
     * the NULL sentinel, and the sequence that ends a `COPY` stream when the file is
     * fed back to PostgreSQL.
     */
    private fun needsQuotes(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text == options.nullText || text == COPY_TERMINATOR) return true
        if (text.first().isWhitespace() || text.last().isWhitespace()) return true
        return text.any { it == DELIMITER || it == '"' || it == '\n' || it == '\r' }
    }

    private fun endRecord() {
        emit('\r')
        emit('\n')
    }

    private fun emit(text: String) {
        out.append(text)
        var count = 0L
        for (character in text) count += utf8Length(character)
        bytes += count
    }

    private fun emit(character: Char) {
        out.append(character)
        bytes += utf8Length(character)
    }

    /**
     * What one UTF-16 unit costs in UTF-8.
     *
     * A character outside the BMP is a surrogate pair encoding to four bytes, so
     * the high half is charged for both and the low half for nothing. That accounting
     * is exact for real text, which is all that reaches here: an unpaired surrogate
     * could only come from a clipped value, and [textOf] refuses those.
     */
    private fun utf8Length(character: Char): Int = when {
        character.code < 0x80 -> 1
        character.code < 0x800 -> 2
        character.isHighSurrogate() -> 4
        character.isLowSurrogate() -> 0
        else -> 3
    }
}
