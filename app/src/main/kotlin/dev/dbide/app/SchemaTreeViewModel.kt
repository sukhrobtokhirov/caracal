package dev.dbide.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshots.SnapshotStateSet
import dev.dbide.core.catalog.CatalogObject
import dev.dbide.core.catalog.ColumnInfo
import dev.dbide.core.catalog.ObjectKind
import dev.dbide.core.catalog.SchemaInfo
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionService
import dev.dbide.core.result.Failure
import dev.dbide.core.result.toFailure
import dev.dbide.core.sql.Identifiers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A node in the tree, by identity rather than by a joined-up string.
 *
 * Schema and object names can contain any character a person can type, including
 * whatever separator a string key would have used, so the key is the parts
 * themselves and equality comes from the data class.
 */
sealed interface NodeKey {
    val schema: String

    data class Schema(override val schema: String) : NodeKey

    /** The "Tables"/"Views"/… grouping under a schema. One catalog query each. */
    data class Folder(override val schema: String, val kind: ObjectKind) : NodeKey

    data class Relation(override val schema: String, val name: String, val kind: ObjectKind) : NodeKey

    data class Column(override val schema: String, val relation: String, val ordinal: Int) : NodeKey
}

/** What is known about one node's children. Absent means never asked. */
sealed interface NodeState<out T> {
    data object Loading : NodeState<Nothing>

    data class Ready<T>(val items: List<T>, val truncated: Boolean = false) : NodeState<T>

    data class Failed(val failure: Failure) : NodeState<Nothing>
}

/** What a row is, which is what decides its glyph. */
enum class RowKind { SCHEMA, FOLDER, TABLE, VIEW, MATERIALIZED_VIEW, FUNCTION, COLUMN }

/**
 * One rendered line of the tree.
 *
 * [loading], [error], and [note] describe this row's *children* — the tree reports
 * a failed expansion on the node that failed, so a schema the user cannot read
 * says so on its own line and the rest of the tree stays where it was.
 */
data class TreeRow(
    val key: NodeKey,
    val depth: Int,
    val label: String,
    val kind: RowKind,
    val detail: String? = null,
    val flags: List<String> = emptyList(),
    val expandable: Boolean = false,
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val note: String? = null,
    /** This node as SQL, quoted. `null` for a row that names nothing insertable. */
    val identifier: String? = null,
)

/**
 * The object browser: schemas, then objects, then columns, each fetched when it is
 * opened and not before.
 *
 * Loading the whole tree up front is one query against a server with four hundred
 * schemas, and it is also four hundred schemas of layout nobody asked for. So each
 * node loads on expansion and stays cached until the user asks for it again — and
 * because the cache lives here rather than in the composables, running a query,
 * switching panes, or scrolling does not collapse anything.
 *
 * Every node loads in its own job, so a slow schema does not hold up a fast one and
 * collapsing a node cancels only its own read.
 */
class SchemaTreeViewModel(
    private val service: ConnectionService,
    private val scope: CoroutineScope,
) {
    /** The connection being browsed, or `null` when nothing is open. */
    var connectionId: ConnectionId? by mutableStateOf(null)
        private set

    /** Whether `pg_catalog`, `information_schema`, and the temporary schemas are listed. */
    var showSystemSchemas: Boolean by mutableStateOf(false)
        private set

    /** The schema list itself: the one piece of state the panel renders directly. */
    var root: NodeState<SchemaInfo>? by mutableStateOf(null)
        private set

    private val objects: SnapshotStateMap<NodeKey.Folder, NodeState<CatalogObject>> = mutableStateMapOf()
    private val columns: SnapshotStateMap<NodeKey.Relation, NodeState<ColumnInfo>> = mutableStateMapOf()

    /** The nodes the user has opened. Survives every reload short of a new connection. */
    private val expanded: SnapshotStateSet<NodeKey> = mutableStateSetOf()

    /** The in-flight read per node; the schema list is the one keyed by `null`. */
    private val jobs = mutableMapOf<NodeKey?, Job>()

    /** The tree, flattened to what is visible right now. */
    val rows: List<TreeRow>
        get() = buildList {
            val schemas = (root as? NodeState.Ready)?.items ?: return@buildList
            schemas.forEach { schema -> appendSchema(schema) }
        }

    /**
     * Points the browser at a connection, or at nothing.
     *
     * Being asked for the connection already shown is not a reason to throw away
     * what the user has opened, which is what keeps the tree still while queries run
     * and panes change.
     */
    fun show(id: ConnectionId?) {
        if (id == connectionId) return
        reset()
        connectionId = id
        if (id != null) loadSchemas()
    }

    fun toggle(key: NodeKey) {
        if (expanded.remove(key)) {
            // Nobody is waiting for children that are no longer on screen.
            jobs.remove(key)?.cancel()
            return
        }
        expanded += key
        load(key)
    }

    fun isExpanded(key: NodeKey): Boolean = key in expanded

    /** Shows or hides the server's own schemas. Only the schema list is re-read. */
    fun toggleSystemSchemas() {
        showSystemSchemas = !showSystemSchemas
        loadSchemas()
    }

    /**
     * Re-reads a node, or the whole connection when [key] is `null`.
     *
     * Cached children under the node are dropped and the ones still expanded are
     * read again immediately: a refresh that emptied the tree and waited for the
     * user to re-open every node would be a worse answer than the stale one.
     */
    fun refresh(key: NodeKey? = null) {
        if (connectionId == null) return
        objects.keys.filter { it.isUnder(key) }.forEach { objects.remove(it) }
        columns.keys.filter { it.isUnder(key) }.forEach { columns.remove(it) }
        if (key == null) loadSchemas()
        // Whatever is still open is read again now. A refresh that emptied the tree
        // and waited for the user to re-open every node would be worse than stale.
        expanded.filter { it.isUnder(key) }.forEach { load(it, force = true) }
    }

    /** Drops everything. The vault locking must not leave a map of the server on screen. */
    fun clear() {
        reset()
        connectionId = null
    }

    // --- Flattening ----------------------------------------------------------

    private fun MutableList<TreeRow>.appendSchema(schema: SchemaInfo) {
        val key = NodeKey.Schema(schema.name)
        val open = key in expanded
        val folders = ObjectKind.entries.map { kind -> NodeKey.Folder(schema.name, kind) }
        val states = folders.map { objects[it] }
        // A schema the user cannot read fails all four ways at once, and saying so
        // four times is not four pieces of information.
        val refused = states.filterIsInstance<NodeState.Failed>()
            .takeIf { it.size == states.size }
            ?.first()
        add(
            TreeRow(
                key = key,
                depth = 0,
                label = schema.name,
                kind = RowKind.SCHEMA,
                detail = schema.owner,
                flags = if (schema.system) listOf("system") else emptyList(),
                expandable = true,
                expanded = open,
                // All four kinds are read together when the schema opens, so the wait
                // belongs to the schema rather than to four separate lines of it.
                loading = open && states.any { it == null || it is NodeState.Loading },
                error = if (open) refused?.failure?.message else null,
                note = if (open && states.all { it is NodeState.Ready && it.items.isEmpty() }) {
                    "This schema has no tables, views, or functions."
                } else {
                    null
                },
                identifier = Identifiers.quote(schema.name),
            ),
        )
        if (!open) return
        folders.forEach { folder -> appendFolder(folder, hideFailure = refused != null) }
    }

    /**
     * A kind's grouping, drawn only when there is something to say about it: a kind
     * this schema has none of is not a line, a kind still being read is the schema's
     * spinner rather than four of its own, and a kind that failed because the whole
     * schema is unreadable is the schema's error rather than four copies of it.
     */
    private fun MutableList<TreeRow>.appendFolder(key: NodeKey.Folder, hideFailure: Boolean) {
        val state = objects[key] ?: return
        val ready = state as? NodeState.Ready
        if (ready != null && ready.items.isEmpty()) return
        if (state is NodeState.Loading) return
        // The schema is already carrying this failure for all four of its kinds.
        if (hideFailure && state is NodeState.Failed) return
        val open = key in expanded
        add(
            TreeRow(
                key = key,
                depth = 1,
                label = key.kind.plural,
                kind = RowKind.FOLDER,
                detail = ready?.items?.size?.toString(),
                expandable = ready != null,
                expanded = open,
                error = (state as? NodeState.Failed)?.failure?.message,
                note = if (open && ready?.truncated == true) {
                    "Showing the first ${ready.items.size}."
                } else {
                    null
                },
            ),
        )
        if (!open || ready == null) return
        ready.items.forEach { item -> appendObject(item) }
    }

    private fun MutableList<TreeRow>.appendObject(target: CatalogObject) {
        val key = NodeKey.Relation(target.schema, target.name, target.kind)
        val open = key in expanded
        val state = columns[key]
        val ready = state as? NodeState.Ready
        add(
            TreeRow(
                key = key,
                depth = 2,
                label = target.name,
                kind = target.kind.rowKind,
                detail = when (target.kind) {
                    // A function is its name and its argument types; two functions can
                    // share the first half.
                    ObjectKind.FUNCTION -> "(${target.signature.orEmpty()})"
                    // Always labelled an estimate, because reltuples is one — and is
                    // whatever the last ANALYZE thought, which may be nothing at all.
                    else -> target.rowEstimate?.let { "~${it.grouped()} rows" }
                },
                // Functions have no columns worth a tree node in v0.1, so they do not open.
                expandable = target.kind != ObjectKind.FUNCTION,
                expanded = open,
                loading = state is NodeState.Loading,
                error = (state as? NodeState.Failed)?.failure?.message,
                note = if (open && ready?.items?.isEmpty() == true) "No columns." else null,
                identifier = Identifiers.qualify(target.schema, target.name),
            ),
        )
        if (!open || ready == null) return
        ready.items.forEach { column -> appendColumn(key, column) }
    }

    private fun MutableList<TreeRow>.appendColumn(relation: NodeKey.Relation, column: ColumnInfo) {
        add(
            TreeRow(
                key = NodeKey.Column(relation.schema, relation.name, column.ordinal),
                depth = 3,
                label = column.name,
                kind = RowKind.COLUMN,
                detail = column.typeName,
                flags = buildList {
                    if (column.primaryKey) add("PK")
                    if (!column.nullable) add("not null")
                    column.default?.let { add("default ${it.clipped()}") }
                },
                identifier = Identifiers.quote(column.name),
            ),
        )
    }

    // --- Loading -------------------------------------------------------------

    private fun loadSchemas() {
        val id = connectionId ?: return
        launch(null, { root = it }) {
            val listing = service.schemas(id, showSystemSchemas)
            NodeState.Ready(listing.items, listing.truncated)
        }
    }

    /**
     * Reads a node's children, unless they are already here or already on their way.
     *
     * Opening a schema reads all four kinds at once — which is what "fetch objects
     * when a schema is expanded" means, and what lets the tree say straight away that
     * a schema has eleven tables and no views. They are four separate jobs, so the
     * one slow kind does not hold up the other three.
     */
    private fun load(key: NodeKey, force: Boolean = false) {
        val id = connectionId ?: return
        when (key) {
            is NodeKey.Schema ->
                ObjectKind.entries.forEach { kind -> load(NodeKey.Folder(key.schema, kind), force) }

            is NodeKey.Folder -> {
                if (!force && objects[key].isSettled) return
                launch(key, { objects[key] = it }) {
                    val listing = service.objects(id, key.schema, key.kind)
                    NodeState.Ready(listing.items, listing.truncated)
                }
            }

            is NodeKey.Relation -> {
                if (!force && columns[key].isSettled) return
                launch(key, { columns[key] = it }) {
                    NodeState.Ready(service.columns(id, key.schema, key.name))
                }
            }

            // A column is a leaf: there is nothing under it to read.
            is NodeKey.Column -> Unit
        }
    }

    /**
     * Runs one node's read, reporting Loading first and a classified failure instead
     * of an exception. A failure lands on [into], which is the node, and never on a
     * banner that would replace the tree.
     */
    private fun <T> launch(
        key: NodeKey?,
        into: (NodeState<T>) -> Unit,
        body: suspend () -> NodeState<T>,
    ) {
        jobs.remove(key)?.cancel()
        into(NodeState.Loading)
        val job = scope.launch {
            val state = try {
                body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (problem: Throwable) {
                NodeState.Failed(problem.toFailure())
            }
            into(state)
        }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
    }

    private fun reset() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        objects.clear()
        columns.clear()
        expanded.clear()
        root = null
    }

    /** Whether this node is [ancestor] or sits beneath it. A `null` ancestor is the connection. */
    private fun NodeKey.isUnder(ancestor: NodeKey?): Boolean = when (ancestor) {
        null -> true
        is NodeKey.Schema -> schema == ancestor.schema
        is NodeKey.Folder -> this == ancestor ||
            (this is NodeKey.Relation && schema == ancestor.schema && kind == ancestor.kind)

        is NodeKey.Relation -> this == ancestor
        is NodeKey.Column -> false
    }

    private companion object {
        /** Whether a node's children are here or on their way; a failure is neither. */
        val NodeState<*>?.isSettled: Boolean
            get() = this is NodeState.Ready || this is NodeState.Loading

        val ObjectKind.plural: String
            get() = when (this) {
                ObjectKind.TABLE -> "Tables"
                ObjectKind.VIEW -> "Views"
                ObjectKind.MATERIALIZED_VIEW -> "Materialized views"
                ObjectKind.FUNCTION -> "Functions"
            }

        val ObjectKind.rowKind: RowKind
            get() = when (this) {
                ObjectKind.TABLE -> RowKind.TABLE
                ObjectKind.VIEW -> RowKind.VIEW
                ObjectKind.MATERIALIZED_VIEW -> RowKind.MATERIALIZED_VIEW
                ObjectKind.FUNCTION -> RowKind.FUNCTION
            }

        /** Grouped by thousands, so a seven-digit estimate can be read at a glance. */
        fun Long.grouped(): String = toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()

        /** A default expression can be a whole subquery; the tree shows the start of one. */
        fun String.clipped(): String = if (length <= 40) this else take(39) + "…"
    }
}
