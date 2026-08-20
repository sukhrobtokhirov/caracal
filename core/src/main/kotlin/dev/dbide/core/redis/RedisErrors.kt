package dev.dbide.core.redis

import dev.dbide.core.result.DbError
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
    fun classify(throwable: Throwable): DbError {
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
        return when {
            AUTH_MARKERS.any { replies.contains(it) } -> DbError.AuthenticationFailed(
                "The server rejected the username or password.",
            )
            DATABASE_MARKERS.any { replies.contains(it) } -> DbError.DatabaseNotFound(
                "That database index does not exist on this server.",
            )
            throwable.causes().any { it is RedisConnectionException } -> DbError.HostUnreachable(
                "The Redis server could not be reached.",
            )
            else -> DbError.ConnectionFailed("The connection to Redis failed.")
        }
    }

    /** Netty's timeout is not on the compile classpath, so it is matched by name. */
    private const val CONNECT_TIMEOUT = "io.netty.channel.ConnectTimeoutException"

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
