package dev.dbide.core.catalog

/**
 * A schema, as the object browser shows it.
 *
 * [system] marks the schemas PostgreSQL owns — `pg_catalog`, `information_schema`,
 * and the `pg_toast`/`pg_temp_*` pairs. They are hidden by default and shown by a
 * toggle rather than removed, because reading `pg_catalog` is a normal thing to
 * want and an IDE that pretends it does not exist is lying about the server.
 *
 * [owner] is `null` only when the server would not say.
 */
data class SchemaInfo(
    val name: String,
    val owner: String? = null,
    val system: Boolean = false,
    /**
     * Whether this role may actually look inside the schema.
     *
     * `pg_catalog` is world-readable, so the browser can list the tables in a schema
     * the user has no `USAGE` on and every one of them will refuse to be selected
     * from. The listing and the permission are genuinely two different facts, and
     * §4.7 asks the empty case to tell them apart: a schema with nothing in it and a
     * schema you are not allowed to look in are the same blank space and completely
     * different problems.
     *
     * `true` when the privilege was not asked about, which is what every caller that
     * builds a `SchemaInfo` by hand does. Absence of an answer is not a denial.
     */
    val usable: Boolean = true,
)

/** The kinds of object the browser can list. Each one is a separate catalog query. */
enum class ObjectKind {
    /** Ordinary and partitioned tables. A partitioned table lists as one table. */
    TABLE,
    VIEW,
    MATERIALIZED_VIEW,
    FUNCTION,
}

/**
 * One object inside a schema.
 *
 * [rowEstimate] is the planner's estimate from `pg_class.reltuples` — free to read
 * and often wrong, which is why it is an estimate and never presented as a count.
 * It is `null` for a relation that has never been analyzed, and for kinds that do
 * not have rows.
 *
 * [signature] carries a function's argument list. v0.1 shows a function's name and
 * signature and nothing else; there is no definition viewer.
 */
data class CatalogObject(
    val schema: String,
    val name: String,
    val kind: ObjectKind,
    val rowEstimate: Long? = null,
    val signature: String? = null,
)

/**
 * One column of a table or view.
 *
 * [typeName] is `format_type`'s rendering — `character varying(20)`, `numeric(10,2)`,
 * `integer[]` — which is the type as a person would write it, not the internal name.
 */
data class ColumnInfo(
    val ordinal: Int,
    val name: String,
    val typeName: String,
    val nullable: Boolean,
    val default: String? = null,
    val primaryKey: Boolean = false,
)

/**
 * A bounded catalog listing. [truncated] means the server had more than
 * [CatalogLimits.items] and the rest was not read.
 *
 * A schema with fifty thousand tables is unusual and entirely possible, and the
 * tree would try to lay out every one of them.
 */
data class Listing<T>(val items: List<T>, val truncated: Boolean = false) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/** What a catalog listing is allowed to cost. Columns need no bound: PostgreSQL caps a relation at 1600. */
data class CatalogLimits(val items: Int = 2_000)
