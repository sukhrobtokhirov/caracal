package dev.caracal.core.redis

import dev.caracal.core.result.DbError
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class RedisErrorsTest {
    @Test
    fun `a rejected password is an authentication failure`() {
        listOf(
            "WRONGPASS invalid username-password pair or user is disabled.",
            "NOAUTH Authentication required.",
            "ERR Client sent AUTH, but no password is set",
        ).forEach { reply ->
            assertIs<DbError.AuthenticationFailed>(
                RedisErrors.classify(RedisCommandExecutionException(reply)),
                reply,
            )
        }
    }

    @Test
    fun `an authentication failure wrapped in a connection failure is still an auth failure`() {
        // How Lettuce actually reports a wrong password at connect time: the outer
        // exception says only that it could not connect.
        val wrapped = RedisConnectionException(
            "Unable to connect to localhost:6379",
            RedisCommandExecutionException("WRONGPASS invalid username-password pair"),
        )

        assertIs<DbError.AuthenticationFailed>(RedisErrors.classify(wrapped))
    }

    @Test
    fun `a database index the server does not have is reported as such`() {
        assertIs<DbError.DatabaseNotFound>(
            RedisErrors.classify(RedisCommandExecutionException("ERR DB index is out of range")),
        )
    }

    @Test
    fun `a certificate problem is its own case, because the fix is not a password`() {
        assertIs<DbError.TlsVerificationFailed>(RedisErrors.classify(SSLHandshakeException("bad cert")))
        assertIs<DbError.TlsVerificationFailed>(
            RedisErrors.classify(RedisConnectionException("failed", CertificateException("untrusted"))),
        )
    }

    @Test
    fun `an unresolvable or refused host is unreachable`() {
        assertIs<DbError.HostUnreachable>(
            RedisErrors.classify(RedisConnectionException("failed", UnknownHostException("nope.invalid"))),
        )
        assertIs<DbError.HostUnreachable>(
            RedisErrors.classify(RedisConnectionException("failed", ConnectException("refused"))),
        )
    }

    @Test
    fun `a command that ran out of time is a timeout`() {
        assertIs<DbError.Timeout>(RedisErrors.classify(RedisCommandTimeoutException("too slow")))
    }

    @Test
    fun `a connection failure with no better explanation is still unreachable`() {
        assertIs<DbError.HostUnreachable>(RedisErrors.classify(RedisConnectionException("unable to connect")))
    }

    @Test
    fun `anything else is a plain connection failure rather than a crash`() {
        assertIs<DbError.ConnectionFailed>(RedisErrors.classify(IllegalStateException("something odd")))
    }

    @Test
    fun `a cause cycle does not hang the classifier`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)

        assertIs<DbError.ConnectionFailed>(RedisErrors.classify(first))
    }

    @Test
    fun `no classified message repeats the server reply, which may name the host`() {
        val reply = "WRONGPASS invalid username-password pair for user at db.internal:6379"

        val error = RedisErrors.classify(RedisCommandExecutionException(reply))

        assertFalse(error.message.contains("db.internal"), error.message)
    }
}
