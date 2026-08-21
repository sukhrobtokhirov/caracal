package dev.dbide.core.redis

import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import java.io.ByteArrayOutputStream

/**
 * A command as it will be sent: an argument array, and the normalized name the
 * policy reads.
 *
 * The array is the primary representation and the command line is a convenience that
 * produces one. §3.9 puts it that way round deliberately: a command assembled from a
 * string somewhere downstream is a command whose argument boundaries were decided
 * twice, and the guard would then be inspecting a different parse from the one Redis
 * performs. Here there is exactly one parse, it happens once, and what the guard
 * inspects is what is sent.
 *
 * [arguments] stays as bytes throughout. A Redis argument is a byte string — a hash
 * field can be a serialized struct, a value can be a JPEG — and round-tripping one
 * through a [String] replaces whatever did not decode. [name] and [subcommand] are
 * text because a *command name* genuinely is ASCII, and they exist only for the
 * policy check.
 */
class RedisCommand private constructor(
    private val raw: List<ByteArray>,
    val name: String,
    val subcommand: String?,
) {
    /** The exact bytes to send. Copied, so nothing downstream can edit them. */
    val arguments: List<ByteArray> get() = raw.map { it.copyOf() }

    val size: Int get() = raw.size

    /**
     * What this command is called, for a message or a log line.
     *
     * The name, plus the subcommand when the command is one of the containers whose
     * subcommand is the whole meaning: `CONFIG` alone says nothing about whether the
     * server is about to be reconfigured. Never any further argument — that is where
     * `AUTH`'s password and `CONFIG SET requirepass`'s value live.
     */
    val label: String = if (subcommand != null && name in CONTAINERS) "$name $subcommand" else name

    /** Never the arguments. This type reaches loggers, and this is what they get. */
    override fun toString(): String = "RedisCommand($label, ${raw.size - 1} arguments)"

    companion object {
        /**
         * Commands whose first argument selects what they do.
         *
         * Only these get their subcommand into [label] and into policy lookups. The
         * list is not about which are dangerous — `CLIENT` is here and `CLIENT GETNAME`
         * is harmless — it is about which ones cannot be judged by name alone.
         */
        private val CONTAINERS = setOf(
            "ACL", "CLIENT", "CLUSTER", "COMMAND", "CONFIG", "LATENCY",
            "MEMORY", "OBJECT", "PUBSUB", "SCRIPT", "SLOWLOG", "XGROUP", "XINFO",
        )

        /**
         * Builds a command from an argument array.
         *
         * The normalization is where a bypass would live, so it is deliberately
         * aggressive and applies only to the copy the policy reads. §3.10 requires
         * that splitting `CONFIG` from `SET` across fields not defeat the check, and
         * the other direction has to hold too — so the first two arguments are
         * *retokenized* on whitespace before the name and subcommand are taken. That
         * way every spelling of the same intent — the two words as two arguments, as
         * one argument, padded with tabs, or in lower case — normalizes to one pair.
         *
         * Retokenizing cannot smuggle anything past the guard, because it only ever
         * produces *more* policy tokens from the same bytes. What is sent is [raw],
         * untouched.
         */
        fun of(arguments: List<ByteArray>): RedisCommand {
            val policyTokens = arguments.take(2).flatMap { argument ->
                // Not decoded strictly: a command name that is not ASCII is not a
                // command, and a lenient decode here only ever yields a token that
                // fails to match an allowlist entry.
                String(argument, Charsets.UTF_8).trim().split(WHITESPACE)
            }.filter { it.isNotEmpty() }.map { it.uppercase() }

            val name = policyTokens.firstOrNull()
            if (arguments.isEmpty() || name.isNullOrEmpty()) {
                throw DbException(DbError.InvalidRequest("Type a command to run."))
            }
            return RedisCommand(
                raw = arguments.map { it.copyOf() },
                name = name,
                subcommand = policyTokens.getOrNull(1),
            )
        }

        /** As [of], for a command whose arguments are all text. */
        fun of(vararg arguments: String): RedisCommand =
            of(arguments.map { it.toByteArray(Charsets.UTF_8) })

        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Splits a typed command line into arguments, the way `redis-cli` does.
 *
 * This is a convenience for the console's text input and nothing more. There is no
 * shell here: no variable expansion, no globbing performed by this code, no command
 * substitution, no pipelines. A dollar sign is a dollar sign, and an asterisk is an
 * asterisk that gets sent to Redis, which has its own opinion about globs and is
 * welcome to it.
 *
 * The escapes are the ones `redis-cli` accepts, because that is the syntax a Redis
 * user already has in their fingers, and a console that silently disagreed with
 * `redis-cli` about what a given escape means would be worse than one with no
 * escapes at all. Note that a hex escape produces a *byte*, which is why the output
 * is byte arrays: one such escape is one byte, and an invalid-UTF-8 byte has to stay
 * that way.
 */
object CommandLine {

    /**
     * The arguments in [line], or a failure describing what is unbalanced.
     *
     * An empty argument is a real argument: `SET k ""` sets a key to the empty string,
     * and an empty pair of quotes here produces a zero-length array rather than being
     * dropped. Only whitespace *between* arguments disappears.
     */
    fun split(line: String): List<ByteArray> {
        val arguments = mutableListOf<ByteArray>()
        var position = 0

        while (true) {
            while (position < line.length && line[position].isWhitespace()) position++
            if (position == line.length) break

            val argument = ByteArrayOutputStream()
            var quote = NONE

            while (position < line.length) {
                val character = line[position]
                when {
                    quote == DOUBLE && character == '\\' -> {
                        position = argument.appendDoubleQuotedEscape(line, position)
                    }
                    // Single quotes take exactly one escape, an escaped single quote,
                    // and treat every other backslash as a literal backslash. That is
                    // Redis's rule and it is also the shell's.
                    quote == SINGLE && character == '\\' && line.getOrNull(position + 1) == '\'' -> {
                        argument.write('\''.code)
                        position += 2
                    }
                    quote != NONE && character == quote -> {
                        // A closing quote ends the argument, and what follows must be a
                        // separator. A quote butted against more text is a typo, not a
                        // two-part argument, and guessing which it was is how a console
                        // runs something other than what was typed.
                        val next = line.getOrNull(position + 1)
                        if (next != null && !next.isWhitespace()) {
                            throw DbException(
                                DbError.InvalidRequest("Put a space after a closing quote."),
                            )
                        }
                        position++
                        quote = NONE
                        break
                    }
                    quote == NONE && (character == '"' || character == '\'') -> {
                        quote = character
                        position++
                    }
                    quote == NONE && character.isWhitespace() -> break
                    else -> {
                        argument.writeUtf8(line, position)
                        position += if (pairedAt(line, position)) 2 else 1
                    }
                }
            }

            if (quote != NONE) {
                throw DbException(DbError.InvalidRequest("There is an unclosed quote in that command."))
            }
            arguments += argument.toByteArray()
        }
        return arguments
    }

    /** [line] parsed and normalized into a command ready for the guard. */
    fun command(line: String): RedisCommand = RedisCommand.of(split(line))

    /**
     * Consumes the escape at [start] and returns the position after it.
     *
     * An unrecognized escape keeps the character that followed the backslash and drops
     * the backslash, which is what `redis-cli` does; a trailing backslash is a literal
     * one, because there is nothing for it to escape.
     */
    private fun ByteArrayOutputStream.appendDoubleQuotedEscape(line: String, start: Int): Int {
        val escaped = line.getOrNull(start + 1) ?: run {
            write('\\'.code)
            return start + 1
        }
        if (escaped == 'x') {
            val high = line.getOrNull(start + 2)?.hexDigit()
            val low = line.getOrNull(start + 3)?.hexDigit()
            if (high != null && low != null) {
                write(high shl 4 or low)
                return start + 4
            }
            // A hex escape without two hex digits is not a byte escape. `redis-cli`
            // keeps the letter literally, and disagreeing would silently change a
            // pattern that happens to contain one.
        }
        when (escaped) {
            'n' -> write('\n'.code)
            'r' -> write('\r'.code)
            't' -> write('\t'.code)
            'b' -> write('\b'.code)
            'a' -> write(0x07)
            else -> {
                // The escape was not one of the recognized ones, so the character keeps
                // itself and only the backslash is dropped — and that character may be
                // a surrogate pair, which the backslash does not split.
                writeUtf8(line, start + 1)
                return start + if (pairedAt(line, start + 1)) 3 else 2
            }
        }
        return start + 2
    }

    /**
     * Writes the source character at [position] as UTF-8, keeping a surrogate pair
     * together.
     *
     * Encoding a lone surrogate produces a question mark, so an emoji typed into the
     * console would arrive at Redis as two of them. The pair is consumed together
     * instead; [pairedAt] tells the caller how far to advance.
     */
    private fun ByteArrayOutputStream.writeUtf8(line: String, position: Int) {
        val end = if (pairedAt(line, position)) position + 2 else position + 1
        write(line.substring(position, end).toByteArray(Charsets.UTF_8))
    }

    /** Whether [position] begins a surrogate pair, and so covers two source characters. */
    private fun pairedAt(line: String, position: Int): Boolean =
        line[position].isHighSurrogate() && line.getOrNull(position + 1)?.isLowSurrogate() == true

    private fun Char.hexDigit(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }

    private const val NONE = ' '
    private const val SINGLE = '\''
    private const val DOUBLE = '"'
}
