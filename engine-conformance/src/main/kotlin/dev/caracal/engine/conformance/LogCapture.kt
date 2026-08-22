package dev.caracal.engine.conformance

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Everything a block of code wrote to a log, wherever it tried to write it.
 *
 * The redaction case has to grep something, and a driver has three plausible places
 * to put a password: `java.util.logging`, which pgjdbc uses; SLF4J, which Lettuce and
 * this build's own code use; and standard error, which is where an SLF4J binding with
 * no configuration ends up anyway. So all three are watched at once — the JUL root
 * logger gets a handler, and both standard streams are teed — and the result is one
 * string.
 *
 * The JUL root level is raised to `ALL` for the duration, because a leak at `FINE` is
 * still a leak: a build that logs the URL at debug level is one support request away
 * from putting a production password in a pasted log file. Existing handlers keep
 * their own levels, so raising it changes what is captured and not what is printed.
 *
 * Teeing rather than replacing is deliberate. A test that swallowed standard error
 * would also swallow the failure diagnostics of everything running inside it.
 */
object LogCapture {

    fun record(body: () -> Unit): String {
        val sink = ByteArrayOutputStream()
        val handler = CollectingHandler(sink)
        val root = Logger.getLogger("")
        val previousLevel = root.level
        val previousOut = System.out
        val previousErr = System.err

        root.level = Level.ALL
        root.addHandler(handler)
        System.setOut(PrintStream(Tee(previousOut, sink), true, Charsets.UTF_8))
        System.setErr(PrintStream(Tee(previousErr, sink), true, Charsets.UTF_8))
        try {
            body()
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            root.removeHandler(handler)
            root.level = previousLevel
            handler.flush()
        }
        return sink.toString(Charsets.UTF_8)
    }

    private class Tee(private val console: PrintStream, private val sink: OutputStream) : OutputStream() {
        override fun write(byte: Int) {
            console.write(byte)
            synchronized(sink) { sink.write(byte) }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            console.write(bytes, offset, length)
            synchronized(sink) { sink.write(bytes, offset, length) }
        }

        override fun flush() {
            console.flush()
            synchronized(sink) { sink.flush() }
        }
    }

    /**
     * A JUL handler that keeps the message, its parameters and its throwable.
     *
     * All three, because a password reaches a log by any of them: in the formatted
     * sentence, as a substituted parameter, or inside the exception a failed connect
     * attached. Formatting is done by hand rather than through a `Formatter`, so that
     * a parameter that will not substitute is still recorded rather than dropped.
     */
    private class CollectingHandler(private val sink: OutputStream) : Handler() {
        init {
            level = Level.ALL
        }

        override fun publish(entry: LogRecord) {
            val text = buildString {
                append(entry.loggerName).append(' ').append(entry.message)
                entry.parameters?.forEach { append(' ').append(it) }
                entry.thrown?.let { append('\n').append(it.stackTraceToString()) }
                append('\n')
            }
            synchronized(sink) { sink.write(text.toByteArray(Charsets.UTF_8)) }
        }

        override fun flush() = synchronized(sink) { sink.flush() }

        override fun close() = Unit
    }
}
