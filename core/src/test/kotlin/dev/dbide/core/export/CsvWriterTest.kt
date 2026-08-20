package dev.dbide.core.export

import dev.dbide.core.result.CellValue
import dev.dbide.core.result.Column
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What ends up in the file.
 *
 * A CSV is read by things that will not ask for clarification, so most of these
 * are about a value surviving the round trip out and back: a comma that does not
 * become two columns, a NULL that does not become an empty string, a `bytea` that
 * PostgreSQL would accept again.
 */
class CsvWriterTest {

    @Test
    fun `a plain result is a header and one record per row`() {
        val csv = write(
            columns = listOf("id", "name"),
            rows = listOf(
                listOf(CellValue.Integer(1), CellValue.Text("Ada")),
                listOf(CellValue.Integer(2), CellValue.Text("Grace")),
            ),
        )

        assertEquals("id,name\r\n1,Ada\r\n2,Grace\r\n", csv)
    }

    @Test
    fun `records end with CRLF, which is what RFC 4180 says and every reader takes`() {
        assertEquals("a\r\n", write(columns = listOf("a"), rows = emptyList()))
    }

    @Test
    fun `duplicate column names are written as they are`() {
        // `SELECT 1 AS a, 2 AS a` is legal, and renaming one here would invent a
        // column the query never produced.
        assertEquals("a,a\r\n", write(columns = listOf("a", "a"), rows = emptyList()))
    }

    // --- Quoting ---------------------------------------------------------------

    @Test
    fun `a delimiter inside a value is quoted rather than allowed to split it`() {
        assertEquals("\"Ada, Countess\"", field(CellValue.Text("Ada, Countess")))
    }

    @Test
    fun `a quote is doubled inside a quoted field`() {
        assertEquals("\"she said \"\"no\"\"\"", field(CellValue.Text("she said \"no\"")))
    }

    @Test
    fun `a line break inside a value is kept exactly and the field is quoted`() {
        // The record separator is CRLF; a newline in the data is data, and rewriting
        // it would change the value to make the file tidier.
        assertEquals("\"first\nsecond\"", field(CellValue.Text("first\nsecond")))
        assertEquals("\"first\rsecond\"", field(CellValue.Text("first\rsecond")))
    }

    @Test
    fun `leading and trailing spaces are quoted so a lenient reader cannot trim them`() {
        assertEquals("\" padded \"", field(CellValue.Text(" padded ")))
        assertEquals("\"tab\t\"", field(CellValue.Text("tab\t")))
        assertEquals("in between", field(CellValue.Text("in between")))
    }

    @Test
    fun `the sequence that ends a COPY stream is quoted`() {
        // A lone `\.` on its own line ends the data when this file is fed back through
        // `\copy`, and everything after it is a syntax error rather than a row.
        assertEquals("\"\\.\"", field(CellValue.Text("\\.")))
        assertEquals("\\.hidden", field(CellValue.Text("\\.hidden")))
    }

    // --- NULL, and the string that looks like it -------------------------------

    @Test
    fun `NULL is the bare sentinel and the string NULL is quoted`() {
        // The whole reason the sentinel is not an empty field: the two have to be
        // distinguishable, and so do the sentinel and a value that happens to spell it.
        val csv = write(
            columns = listOf("missing", "spelled", "empty"),
            rows = listOf(listOf(CellValue.Null, CellValue.Text("NULL"), CellValue.Text(""))),
        )

        assertEquals("missing,spelled,empty\r\nNULL,\"NULL\",\r\n", csv)
    }

    @Test
    fun `an empty string is an empty field, which is not what NULL looks like`() {
        assertEquals("", field(CellValue.Text("")))
        assertEquals("NULL", field(CellValue.Null))
    }

    @Test
    fun `the null representation can be something else`() {
        val options = CsvOptions(nullText = "\\N")

        assertEquals("\\N", field(CellValue.Null, options))
        assertEquals("\"\\N\"", field(CellValue.Text("\\N"), options))
    }

    @Test
    fun `a null representation that would need quoting is refused`() {
        // It is written unquoted by definition, so one containing a delimiter or a
        // line break would not be a sentinel — it would be two fields, or two rows.
        for (broken in listOf(",", "a,b", "\"", "line\nbreak", "carriage\rreturn")) {
            assertThrows<IllegalArgumentException>("accepted $broken") { CsvOptions(nullText = broken) }
        }
    }

    // --- Values ----------------------------------------------------------------

    @Test
    fun `an int8 past what a double holds keeps every digit`() {
        assertEquals("9007199254740993", field(CellValue.Integer(9007199254740993L)))
    }

    @Test
    fun `a numeric keeps its scale`() {
        assertEquals("1.00000", field(CellValue.Decimal(BigDecimal("1.00000"))))
    }

    @Test
    fun `a small number is written in full rather than as an exponent`() {
        // `1E-10` is the same value and is at the mercy of whatever parses it next.
        assertEquals("0.0000000001", field(CellValue.Decimal(BigDecimal("1e-10"))))
    }

    @Test
    fun `a boolean is written as a word both PostgreSQL and a spreadsheet accept`() {
        assertEquals("true", field(CellValue.Bool(true)))
        assertEquals("false", field(CellValue.Bool(false)))
    }

    @Test
    fun `bytea keeps PostgreSQL's own hexadecimal form`() {
        assertEquals("\\xdeadbeef", field(CellValue.Binary("\\xdeadbeef", byteCount = 4, truncated = false)))
    }

    @Test
    fun `a value the grid clipped is refused rather than written as a prefix`() {
        // A CSV of prefixes looks exactly like a CSV of values. Nothing downstream
        // could ever notice, which is why this is a crash and not a flag.
        assertThrows<IllegalArgumentException> {
            field(CellValue.Text("clipped", truncated = true))
        }
        assertThrows<IllegalArgumentException> {
            field(CellValue.Binary("\\xde", byteCount = 4096, truncated = true))
        }
    }

    // --- Size ------------------------------------------------------------------

    @Test
    fun `the byte count is UTF-8 and exact, including outside the BMP`() {
        // Asserted by every call to write(), because a byte budget that counted
        // Kotlin's UTF-16 units would be out by up to four times on exactly the text
        // most likely to be large.
        val csv = write(
            columns = listOf("ключ", "значение"),
            rows = listOf(
                listOf(CellValue.Text("naïve"), CellValue.Text("🙂🙂🙂")),
                listOf(CellValue.Text("日本語"), CellValue.Null),
            ),
        )

        // The equality itself is asserted in write(), for this document and every
        // other one in the file. What this case adds is text where the two counts
        // could not accidentally agree.
        assertTrue(
            csv.toByteArray(Charsets.UTF_8).size > csv.length,
            "the sample has to hold multibyte text for the comparison to prove anything",
        )
    }

    private fun field(cell: CellValue, options: CsvOptions = CsvOptions()): String =
        write(columns = emptyList(), rows = listOf(listOf(cell)), options = options)
            .removePrefix("\r\n")
            .removeSuffix("\r\n")

    /** Writes a document, and checks the writer's own accounting of it while it is here. */
    private fun write(
        columns: List<String>,
        rows: List<List<CellValue>>,
        options: CsvOptions = CsvOptions(),
    ): String {
        val out = StringBuilder()
        val writer = CsvWriter(out, options)
        writer.header(columns.map { Column(name = it, typeName = "text") })
        rows.forEach { writer.row(it) }

        val csv = out.toString()
        assertEquals(csv.toByteArray(Charsets.UTF_8).size.toLong(), writer.bytes, "byte count disagrees with the file")
        return csv
    }
}
