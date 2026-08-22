package dev.caracal.engine.api

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * §3.9's tokenizer, and the normalization the guard reads.
 *
 * The tokenizer decides what a typed line *is*, so a disagreement between it and
 * `redis-cli` is a console that runs something other than what the user believes
 * they wrote. The normalization decides what the guard *sees*, so a gap between the
 * two is a way past the guard — which is why most of the second half is about
 * spellings nobody would type on purpose.
 */
class CommandLineTest {

    // --- Splitting ------------------------------------------------------------

    @Test
    fun `a plain line splits on whitespace`() {
        assertEquals(listOf("GET", "user:42"), CommandLine.split("GET user:42").texts())
    }

    @Test
    fun `runs of whitespace between arguments collapse`() {
        assertEquals(listOf("GET", "k"), CommandLine.split("   GET \t  k  ").texts())
    }

    @Test
    fun `a quoted argument keeps its spaces`() {
        assertEquals(
            listOf("SET", "greeting", "hello there"),
            CommandLine.split("""SET greeting "hello there"""").texts(),
        )
    }

    @Test
    fun `single quotes work too, and take no escapes but their own`() {
        assertEquals(listOf("SET", "k", """a\nb"""), CommandLine.split("""SET k 'a\nb'""").texts())
        assertEquals(listOf("SET", "k", "it's"), CommandLine.split("""SET k 'it\'s'""").texts())
    }

    @Test
    fun `an empty argument is an argument`() {
        // `SET k ""` sets a key to the empty string. Dropping the empty token would
        // turn it into a two-argument SET, which is an arity error rather than a
        // different command — but on `LPUSH k ""` it would silently push the wrong
        // thing.
        val arguments = CommandLine.split("""SET k ""  """)

        assertEquals(3, arguments.size)
        assertEquals(0, arguments[2].size)
    }

    @Test
    fun `the standard escapes mean what redis-cli means by them`() {
        val value = CommandLine.split(""""a\nb\tc\r\\d\"e"""").single()

        assertEquals("a\nb\tc\r\\d\"e", String(value))
    }

    @Test
    fun `a hex escape produces one byte, including one that is not text`() {
        val value = CommandLine.split(""""\xff\x00\x41"""").single()

        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x00, 0x41), value)
        // And that byte survives classification as binary rather than being replaced.
        assertIs<TextValue.Binary>(TextValues.of(value, limit = 16))
    }

    @Test
    fun `an incomplete hex escape stays literal, as redis-cli leaves it`() {
        assertEquals("xZZ", String(CommandLine.split(""""\xZZ"""").single()))
    }

    @Test
    fun `an unknown escape keeps the character and drops the backslash`() {
        assertEquals("q*", String(CommandLine.split(""""\q\*"""").single()))
    }

    @Test
    fun `characters outside the basic plane survive as themselves`() {
        // A lone surrogate encodes to a question mark, so a naive per-character
        // tokenizer sends `??` to Redis and the key is not the key that was typed.
        val value = CommandLine.split("GET 🧊:42").last()

        assertEquals("🧊:42", String(value))
        assertContentEquals("🧊:42".toByteArray(), value)
    }

    @Test
    fun `an escaped character outside the basic plane also survives`() {
        assertEquals("🧊", String(CommandLine.split(""""\🧊"""").single()))
    }

    @Test
    fun `an unclosed quote is refused rather than guessed at`() {
        val failure = assertThrows<InvalidRequestException> { CommandLine.split("""SET k "unterminated""") }

        assertTrue(failure.message.isNotBlank())
    }

    @Test
    fun `text butted against a closing quote is refused`() {
        // `"a"b` could be one argument or two. A console that picks one runs a command
        // the user did not write, roughly half the time.
        assertThrows<InvalidRequestException> { CommandLine.split("""SET k "a"b""") }
    }

    @Test
    fun `an empty line has no command in it`() {
        assertTrue(CommandLine.split("   ").isEmpty())
        val failure = assertThrows<InvalidRequestException> { CommandLine.command("   ") }
        assertTrue(failure.message.isNotBlank())
    }

    @Test
    fun `nothing in a command line is expanded`() {
        // There is no shell here. Every one of these reaches Redis verbatim, which is
        // what makes a glob a glob rather than a list of files in the current
        // directory.
        val arguments = CommandLine.split("""SCAN 0 MATCH ${'$'}HOME/*.rdb""").texts()

        assertEquals(listOf("SCAN", "0", "MATCH", "\$HOME/*.rdb"), arguments)
    }

    // --- Normalization the guard reads ---------------------------------------

    @Test
    fun `a command names itself in upper case whatever was typed`() {
        assertEquals("GET", CommandLine.command("get k").name)
        assertEquals("GET", CommandLine.command("  GeT   k ").name)
    }

    @Test
    fun `a container command carries its subcommand in its label`() {
        assertEquals("CONFIG SET", CommandLine.command("config set maxmemory 1gb").label)
        assertEquals("CONFIG GET", CommandLine.command("CONFIG GET maxmemory").label)
    }

    @Test
    fun `a command that is not a container keeps its subcommand out of its label`() {
        // `GET SET` is a GET of a key called SET, and calling it "GET SET" in a
        // refusal message would name a command that does not exist.
        assertEquals("GET", CommandLine.command("GET set").label)
    }

    @Test
    fun `every spelling of a container command normalizes to one label`() {
        // The bypass §3.10 names, and its mirror image. Splitting the two words across
        // arguments, joining them into one, padding them, and changing their case all
        // have to reach the guard as the same thing.
        val spellings = listOf(
            RawCommand.of("CONFIG", "SET", "appendonly", "no"),
            RawCommand.of("config", "set", "appendonly", "no"),
            RawCommand.of("CONFIG SET", "appendonly", "no"),
            RawCommand.of("  config  ", "  set  ", "appendonly", "no"),
            RawCommand.of("\tCONFIG\t", "\nset\n", "appendonly", "no"),
        )

        for (command in spellings) {
            assertEquals("CONFIG SET", command.label, "normalized to ${command.label}")
        }
    }

    @Test
    fun `normalization never changes the bytes that will be sent`() {
        // The normalization is aggressive on purpose, and would be a bug if it reached
        // the wire: retokenizing `CONFIG SET` into two arguments and sending that would
        // turn a command Redis rejects into one it accepts.
        val command = RawCommand.of("CONFIG SET", "appendonly", "no")

        assertEquals("CONFIG SET", command.label)
        assertEquals(3, command.size)
        assertEquals(listOf("CONFIG SET", "appendonly", "no"), command.arguments.texts())
    }

    @Test
    fun `arguments are copied, so nothing can be edited after the guard has read it`() {
        val original = "value".toByteArray()
        val command = RawCommand.of(listOf("SET".toByteArray(), "k".toByteArray(), original))
        original[0] = 'X'.code.toByte()

        assertEquals("value", String(command.arguments[2]))
    }

    @Test
    fun `a command with no arguments beyond its name has no subcommand`() {
        assertNull(CommandLine.command("PING").subcommand)
    }

    @Test
    fun `a command never puts its arguments in its own description`() {
        // This type reaches loggers. `AUTH` and `SET session:x <token>` are the reason
        // it must not carry what it was given.
        val rendered = RawCommand.of("AUTH", "admin", "hunter2").toString()

        assertTrue(rendered.contains("AUTH"))
        assertTrue(!rendered.contains("hunter2") && !rendered.contains("admin"), rendered)
    }

    private fun List<ByteArray>.texts(): List<String> = map { String(it) }
}
