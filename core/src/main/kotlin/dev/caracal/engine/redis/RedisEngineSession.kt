package dev.caracal.engine.redis

import dev.caracal.core.redis.RedisSession
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.EngineCapabilities
import dev.caracal.engine.api.EngineId
import dev.caracal.engine.api.ServerVersion
import dev.caracal.engine.api.SessionState
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A live Redis connection, seen through the SPI.
 *
 * It provides no facets, and that is a deliberate Phase 1 answer rather than an
 * omission. Redis's three — the key browser, the console, and the INFO dashboard —
 * have exactly one consumer between them, which is the UI, and their shape is
 * decided entirely by what that UI needs to draw: how a page of keys is requested,
 * what a value page carries, what a RESP reply tree looks like once it is no longer
 * Lettuce's. Deciding that here, one phase before the flip that would tell us, is
 * how a facet ends up being the wrong shape in a module nobody may change without
 * touching every engine. They arrive in Phase 2, with a caller to answer to.
 *
 * What this does provide is the part that is genuinely engine-side and settled:
 * identity, capabilities, a version, a ping, and a lifecycle — plus, on
 * [RedisEngine], the intent classifier, which is §7's per-engine half and can be
 * written and tested with no UI at all.
 */
class RedisEngineSession internal constructor(
    private val session: RedisSession,
    override val serverVersion: ServerVersion,
) : DatabaseSession {

    override val engineId: EngineId = RedisEngine.ID

    override val capabilities: EngineCapabilities = RedisEngine.CAPABILITIES

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Ready)

    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /** The Redis client's own view of this server. Removed once the facets arrive. */
    val adapter get() = session.adapter

    override suspend fun ping(): Duration {
        val started = TimeSource.Monotonic.markNow()
        try {
            session.ping()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: DbException) {
            mutableState.value = SessionState.Broken(failure.error.message)
            throw failure
        }
        mutableState.value = SessionState.Ready
        return started.elapsedNow()
    }

    override fun <F : Any> facet(type: KClass<F>): F? = null

    override fun close() {
        mutableState.value = SessionState.Closed
        session.close()
    }
}
