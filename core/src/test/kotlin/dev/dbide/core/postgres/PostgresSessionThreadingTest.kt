package dev.dbide.core.postgres

import dev.dbide.core.connections.Secret
import dev.dbide.core.connections.TlsMode
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Opening a connection must not do its blocking work on the caller's thread.
 *
 * This is a regression test. The application shipped a `PostgresSession.open` with no
 * dispatcher switch of its own, so the HikariCP pool was constructed — and on the
 * failure path closed again — on whichever thread called it. From the UI that was the
 * AWT event thread, which showed up in the logs as
 * `[AWT-EventQueue-0] dbide-postgres - Starting...`.
 */
class PostgresSessionThreadingTest {

    /** Counts the coroutines actually handed to it, which is the whole assertion. */
    private class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        @Volatile
        var dispatches = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    /** Port 1 is reserved and closed, so this fails without needing a server. */
    private val unreachable = PostgresConnectionConfig(
        host = "127.0.0.1",
        port = 1,
        database = "dbide",
        user = "dbide",
        password = Secret(""),
        tlsMode = TlsMode.DISABLE,
    )

    @Test
    fun `opening moves onto the dispatcher it was given, rather than running inline`() = runBlocking {
        val dispatcher = RecordingDispatcher(Dispatchers.IO)

        runCatching { PostgresSession.open(unreachable, dispatcher = dispatcher) }

        // Without the withContext, open runs on the caller and this stays at zero,
        // whether the dial succeeds or fails.
        assertTrue(dispatcher.dispatches > 0, "open() never left the calling thread")
    }
}
