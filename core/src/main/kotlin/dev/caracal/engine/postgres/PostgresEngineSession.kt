package dev.caracal.engine.postgres

import dev.caracal.core.postgres.PostgresCatalog
import dev.caracal.core.postgres.PostgresSession
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.CatalogFacet
import dev.caracal.engine.api.CatalogObject
import dev.caracal.engine.api.ColumnInfo
import dev.caracal.engine.api.DatabaseSession
import dev.caracal.engine.api.EngineCapabilities
import dev.caracal.engine.api.EngineId
import dev.caracal.engine.api.Listing
import dev.caracal.engine.api.ObjectKind
import dev.caracal.engine.api.SchemaInfo
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
 * A live PostgreSQL connection, seen through the SPI.
 *
 * A wrapper and nothing more: every method here forwards to `PostgresSession` and
 * the adapter it already owns. Phase 1 is a refactor, so the rule is that no
 * behaviour arrives with the interface — what the UI gets when it eventually asks
 * this object a question must be exactly what it gets from the concrete session
 * today, or the Phase 0 tests are guarding nothing.
 *
 * `PostgresSession` was left implementing nothing, rather than being made to
 * implement [DatabaseSession] directly, for one concrete reason: [serverVersion] is
 * a value and not a call, so a session that has one has to read it while connecting.
 * Putting that on `PostgresSession` would add a step to the path the registry
 * already uses, which is precisely the sort of change this phase must not make.
 */
class PostgresEngineSession internal constructor(
    private val session: PostgresSession,
    override val serverVersion: ServerVersion,
) : DatabaseSession {

    override val engineId: EngineId = PostgresEngine.ID

    override val capabilities: EngineCapabilities = PostgresEngine.CAPABILITIES

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Ready)

    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val facets = listOf<Any>(
        PostgresQueryFacet(session.adapter),
        PostgresCatalogFacet(session.catalog),
    )

    /**
     * One round trip, and how long it took.
     *
     * A failed ping moves the session to [SessionState.Broken] and rethrows. Both
     * halves matter: the caller still needs the classified error, and the connection
     * list needs to tell "this broke" apart from "this was closed", because only one
     * of those is worth offering to reopen.
     */
    override suspend fun ping(): Duration {
        val started = TimeSource.Monotonic.markNow()
        try {
            session.adapter.selectOne()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: DbException) {
            mutableState.value = SessionState.Broken(failure.error.message)
            throw failure
        }
        mutableState.value = SessionState.Ready
        return started.elapsedNow()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <F : Any> facet(type: KClass<F>): F? =
        facets.firstOrNull { type.isInstance(it) } as F?

    override fun close() {
        mutableState.value = SessionState.Closed
        session.close()
    }
}

/**
 * The object browser's view, forwarded to `PostgresCatalog` unchanged.
 *
 * There is nothing to translate: the catalog vocabulary moved into `:engine-api`
 * wholesale, so what `PostgresCatalog` already returns is what the facet is declared
 * to return. The queries behind it stay where they are and stay PostgreSQL's own —
 * `pg_catalog` and `information_schema` are not the same shape, and a shared catalog
 * query is how a browser becomes the intersection of every engine it supports.
 */
internal class PostgresCatalogFacet(private val catalog: PostgresCatalog) : CatalogFacet {

    override suspend fun schemas(includeSystem: Boolean): Listing<SchemaInfo> =
        catalog.schemas(includeSystem)

    override suspend fun objects(schema: String, kind: ObjectKind): Listing<CatalogObject> =
        catalog.objects(schema, kind)

    override suspend fun columns(schema: String, relation: String): List<ColumnInfo> =
        catalog.columns(schema, relation)
}
