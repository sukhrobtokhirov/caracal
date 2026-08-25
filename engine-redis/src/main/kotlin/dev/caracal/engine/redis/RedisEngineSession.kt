package dev.caracal.engine.redis

import dev.caracal.core.redis.RedisAdapter
import dev.caracal.core.redis.RedisSession
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.CommandConsent
import dev.caracal.engine.api.CommandFacet
import dev.caracal.engine.api.CommandResult
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.EngineCapabilities
import dev.caracal.engine.api.EngineId
import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.KeyValueFacet
import dev.caracal.engine.api.KeyValueLimits
import dev.caracal.engine.api.MetricsFacet
import dev.caracal.engine.api.RawCommand
import dev.caracal.engine.api.ScanCursor
import dev.caracal.engine.api.ScanPage
import dev.caracal.engine.api.ServerInfo
import dev.caracal.engine.api.ServerVersion
import dev.caracal.engine.api.SessionState
import dev.caracal.engine.api.ValuePage
import dev.caracal.engine.api.ValueRequest
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
 * It provides the three facets Phase 1 deferred — [KeyValueFacet], [CommandFacet]
 * and [MetricsFacet] — and every one of them forwards to the [RedisAdapter] the
 * session already owns. There is nothing to translate: the vocabulary those
 * interfaces are declared in moved into `:engine-api` wholesale, so what the adapter
 * already returns is what the facets are declared to return.
 *
 * The forwarding is the point rather than an apology for it. The adapter is where
 * every bound in the product lives — no `KEYS`, no `HGETALL`, no scan loop without a
 * stop — and a facet that reimplemented any of that would be a second place for
 * those rules to be got wrong. What the facets add is that the key browser, the
 * console and the dashboard can now be handed one, and can no longer be handed a
 * Redis client.
 */
class RedisEngineSession internal constructor(
    private val session: RedisSession,
    override val serverVersion: ServerVersion,
) : DatabaseSession {

    override val engineId: EngineId = RedisEngine.ID

    override val capabilities: EngineCapabilities = RedisEngine.CAPABILITIES

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Ready)

    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val facets = listOf<Any>(
        RedisKeyValueFacet(session.adapter),
        RedisCommandFacet(session.adapter),
        RedisMetricsFacet(session.adapter),
    )

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

    /**
     * This session's implementation of [type], or null.
     *
     * Null is a real answer and the integration suite asserts it: asking a key-value
     * engine for a [dev.caracal.engine.api.QueryFacet] returns nothing rather than a
     * surprise, which is the facet model working rather than failing.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <F : Any> facet(type: KClass<F>): F? =
        facets.firstOrNull { type.isInstance(it) } as F?

    override fun close() {
        mutableState.value = SessionState.Closed
        session.close()
    }
}

/**
 * The key browser's reads, forwarded to [RedisAdapter] unchanged.
 *
 * [limits] is exposed rather than kept private because the caller has to be able to
 * say what it was cut at — "the first 4 KB of a 40 MB value" is a sentence the value
 * viewer draws, and it needs the number to draw it.
 */
internal class RedisKeyValueFacet(private val adapter: RedisAdapter) : KeyValueFacet {

    override val limits: KeyValueLimits get() = adapter.limits

    override suspend fun scan(
        cursor: ScanCursor,
        match: String?,
        type: KeyType?,
        count: Int?,
        pageSize: Int?,
    ): ScanPage = adapter.scan(cursor = cursor, match = match, type = type, count = count, pageSize = pageSize)

    override suspend fun metadata(key: KeyRef): KeyMetadata = adapter.metadata(key)

    override suspend fun value(request: ValueRequest): ValuePage = adapter.value(request)
}

/**
 * The console, forwarded to [RedisAdapter] — which is where the guard is consulted.
 *
 * That the guard is on the far side of this call and not on this one is deliberate.
 * The facet is the whole of the console's access to the server, and it cannot be
 * used to send a command that has not been through `RedisCommandGuard`, because the
 * adapter asks on every call and there is no other door.
 */
internal class RedisCommandFacet(private val adapter: RedisAdapter) : CommandFacet {

    override suspend fun execute(command: RawCommand, consent: CommandConsent): CommandResult =
        adapter.execute(command, consent)
}

/** The dashboard's read. A refused `INFO` comes back restricted, never as a failure. */
internal class RedisMetricsFacet(private val adapter: RedisAdapter) : MetricsFacet {

    override suspend fun info(): ServerInfo = adapter.info()
}
