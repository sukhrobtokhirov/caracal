package dev.caracal.engine.sqlite

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
 * An open SQLite database, seen through the SPI.
 *
 * The two facets a SQL engine owes — [dev.caracal.engine.api.QueryFacet] and
 * [CatalogFacet] — and nothing else. What is absent is as much the point as what is
 * here: there is no transaction facet, no mutation facet, no metrics facet, and no
 * boolean anywhere saying so. A caller asks for a facet and either gets one or does
 * not, which is what stops an engine with fewer features from being an engine with
 * more `if`s around it.
 */
class SqliteEngineSession internal constructor(
    private val session: SqliteSession,
    override val serverVersion: ServerVersion,
) : DatabaseSession {

    override val engineId: EngineId = SqliteEngine.ID

    override val capabilities: EngineCapabilities = SqliteEngine.CAPABILITIES

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Ready)

    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private val facets = listOf<Any>(
        SqliteQueryFacet(session.adapter),
        SqliteCatalogFacet(session.catalog),
    )

    /**
     * One round trip, and how long it took.
     *
     * A failed ping moves the session to [SessionState.Broken] and rethrows. Both
     * halves matter, and the first one earns its keep here more than it does for a
     * server: a database file can be deleted, moved, or unmounted underneath an open
     * connection, and this is where the window finds out — the connection list needs
     * to tell "this broke" apart from "this was closed", because only one of those is
     * worth offering to reopen.
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
 * The object browser's view, forwarded to [SqliteCatalog] unchanged.
 *
 * There is nothing to translate: the catalog vocabulary lives in `:engine-api`, so
 * what [SqliteCatalog] returns is what the facet is declared to return. The queries
 * behind it stay SQLite's own — `sqlite_schema` and `pg_catalog` are not the same
 * shape, and a shared catalog query is how a browser becomes the intersection of every
 * engine it supports.
 */
internal class SqliteCatalogFacet(private val catalog: SqliteCatalog) : CatalogFacet {

    override suspend fun schemas(includeSystem: Boolean): Listing<SchemaInfo> =
        catalog.schemas(includeSystem)

    override suspend fun objects(schema: String, kind: ObjectKind): Listing<CatalogObject> =
        catalog.objects(schema, kind)

    override suspend fun columns(schema: String, relation: String): List<ColumnInfo> =
        catalog.columns(schema, relation)
}
