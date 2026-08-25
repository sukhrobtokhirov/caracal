package dev.caracal.core.redis

import dev.caracal.core.result.DbError
import dev.caracal.core.text.Redaction
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Maps a Lettuce failure onto [DbError], using the same vocabulary as the
 * PostgreSQL adapter so the UI handles one set of cases for both engines.
 *
 * Unlike JDBC there is no SQLSTATE here: Redis reports authentication problems as
 * ordinary error replies, so for those the reply text is the only signal there is.
 * Everything else is classified by exception type, which is stable.
 */
object RedisErrors {

    /**
     * [redaction] scrubs anything that reaches the caller.
     *
     * It matters more for a command failure than for a connection one. Lettuce puts
     * the address into most connection messages, which is bad enough; a server error
     * reply is *server-authored text about the arguments that were sent*, and
     * `NOPERM ... has no permissions to access one of the keys` quotes a key name that
     * may itself be a customer identifier.
     */
    fun classify(throwable: Throwable, redaction: Redaction = Redaction.NONE): DbError {
        throwable.causes().forEach { cause ->
            when (cause) {
                is SSLException, is CertificateException -> return DbError.TlsVerificationFailed()
                is RedisCommandTimeoutException, is SocketTimeoutException -> return DbError.Timeout()
                is UnknownHostException, is ConnectException -> return DbError.HostUnreachable()
            }
            if (cause::class.java.name == CONNECT_TIMEOUT) return DbError.Timeout()
        }

        // The reply text has to be read from the whole chain, not just the top:
        // an authentication failure during connect arrives wrapped in a
        // RedisConnectionException whose own message says only "unable to connect".
        val replies = throwable.causes().mapNotNull { it.message }.joinToString(" ").uppercase()
        val executionFailure = throwable.causes().filterIsInstance<RedisCommandExecutionException>().firstOrNull()
        return when {
            AUTH_MARKERS.any { replies.contains(it) } -> DbError.AuthenticationFailed(
                "The server rejected the username or password.",
            )
            DATABASE_MARKERS.any { replies.contains(it) } -> DbError.DatabaseNotFound(
                "That database index does not exist on this server.",
            )
            // A command the server refused, rather than a connection that failed. It is
            // the ordinary outcome of a console session — `WRONGTYPE`, `NOPERM`, a
            // wrong arity — and reporting it as a broken connection would send the user
            // to reconnect over a typo.
            executionFailure != null -> DbError.QueryFailed(
                message = redaction.scrub(executionFailure.message)?.take(MAX_REPLY_LENGTH)
                    ?: "The server refused that command.",
                severity = executionFailure.errorCode(),
            )
            throwable.causes().any { it is RedisConnectionException } -> DbError.HostUnreachable(
                "The Redis server could not be reached.",
            )
            else -> DbError.ConnectionFailed("The connection to Redis failed.")
        }
    }

    /**
     * The leading word of a Redis error reply — `WRONGTYPE`, `NOPERM`, `NOSCRIPT`.
     *
     * Redis's closest thing to a machine-readable code, and it is only nearly one:
     * the convention is a leading upper-case token, and a plain `ERR` covers most of
     * what can go wrong. So it is carried where PostgreSQL's severity goes, to be
     * shown rather than branched on, and only when it really is one bare token —
     * anything else is prose and belongs in the message.
     */
    private fun RedisCommandExecutionException.errorCode(): String? =
        message?.trim()?.substringBefore(' ')?.takeIf { code ->
            code.isNotEmpty() && code.length <= 24 && code.all { it in 'A'..'Z' }
        }

    /** Netty's timeout is not on the compile classpath, so it is matched by name. */
    private const val CONNECT_TIMEOUT = "io.netty.channel.ConnectTimeoutException"

    /** A server can be persuaded to echo a great deal back; an error line is not a document. */
    private const val MAX_REPLY_LENGTH = 2_048

    private val AUTH_MARKERS = listOf(
        "WRONGPASS",
        "NOAUTH",
        "INVALID PASSWORD",
        "INVALID USERNAME-PASSWORD PAIR",
        "CLIENT SENT AUTH, BUT NO PASSWORD IS SET",
    )

    private val DATABASE_MARKERS = listOf("DB INDEX IS OUT OF RANGE", "SELECT IS NOT ALLOWED")

    /** Bounded: a cause chain can, in principle, contain a cycle. */
    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)

    private const val MAX_CAUSE_DEPTH = 20
}
