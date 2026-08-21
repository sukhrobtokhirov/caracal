package dev.caracal.core.postgres

import dev.caracal.core.catalog.CatalogLimits
import dev.caracal.core.catalog.CatalogObject
import dev.caracal.core.catalog.ColumnInfo
import dev.caracal.core.catalog.Listing
import dev.caracal.core.catalog.ObjectKind
import dev.caracal.core.catalog.SchemaInfo
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.text.Redaction
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Reads the object tree out of `pg_catalog`.
 *
 * Not `information_schema`: it is the SQL standard's view of a database and so
 * describes only the parts of PostgreSQL the standard has a word for. A
 * materialized view is invisible there, a partitioned table is indistinguishable
 * from an ordinary one, and the planner's row estimate does not exist at all. It is
 * also slower, because each of its views joins and filters on privileges.
 *
 * Each read is one bounded statement. Nothing here is cached: caching is a question
 * about what the user is looking at, which belongs to the UI and not to a class
 * whose job is to answer "what is in this schema right now".
 */
class PostgresCatalog(
    private val dataSource: DataSource,
    private val redaction: Redaction = Redaction.NONE,
    private val queryTimeout: Duration = 10.seconds,
    private val limits: CatalogLimits = CatalogLimits(),
) {
    private val log = LoggerFactory.getLogger(PostgresCatalog::class.java)

    /**
     * The schemas on the server.
     *
     * [includeSystem] adds `pg_catalog`, `information_schema`, and the toast and
     * temporary schemas. They are excluded in SQL rather than filtered out
     * afterwards, so a server carrying hundreds of temporary schemas cannot push the
     * user's own past the listing bound.
     */
    suspend fun schemas(includeSystem: Boolean = false): Listing<SchemaInfo> =
        list(if (includeSystem) ALL_SCHEMAS else USER_SCHEMAS) { rows ->
            val name = rows.getString(1)
            SchemaInfo(
                name = name,
                owner = rows.getString(2),
                system = isSystem(name),
                usable = rows.getBoolean(3),
            )
        }

    /**
     * The objects of one [kind] in one schema.
     *
     * The schema travels as a parameter and the kind through the fixed allowlist on
     * [relkinds]; neither is ever pasted into the statement. Today every schema name
     * this is called with came from the server itself, and the day that stops being
     * true is not the day to find out that this built SQL by concatenation.
     */
    suspend fun objects(schema: String, kind: ObjectKind): Listing<CatalogObject> {
        schema.requireName()
        return when (kind) {
            ObjectKind.FUNCTION -> list(FUNCTIONS, schema) { rows ->
                CatalogObject(
                    schema = schema,
                    name = rows.getString(1),
                    kind = kind,
                    signature = rows.getString(2).orEmpty(),
                )
            }

            else -> list(RELATIONS, schema, kind.relkinds) { rows ->
                CatalogObject(
                    schema = schema,
                    name = rows.getString(1),
                    kind = kind,
                    rowEstimate = rows.getLong(2).takeUnless { rows.wasNull() },
                )
            }
        }
    }

    /**
     * The columns of one table, view, or materialized view, in declaration order.
     *
     * Unbounded on purpose: PostgreSQL caps a relation at 1600 columns, so the answer
     * is small by construction and a truncation flag would only ever say `false`.
     */
    suspend fun columns(schema: String, relation: String): List<ColumnInfo> {
        schema.requireName()
        relation.requireName()
        return list(COLUMNS, schema, relation) { rows ->
            ColumnInfo(
                ordinal = rows.getInt(1),
                name = rows.getString(2),
                typeName = rows.getString(3),
                nullable = rows.getBoolean(4),
                default = rows.getString(5),
                primaryKey = rows.getBoolean(6),
            )
        }.items
    }

    /**
     * Runs one catalog statement and maps its rows, reading a row past the bound so a
     * truncated listing is detectable rather than silently complete-looking.
     */
    private suspend fun <T> list(
        sql: String,
        vararg parameters: String,
        map: (ResultSet) -> T,
    ): Listing<T> = withContext(Dispatchers.IO) {
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.configure(parameters)
                    statement.executeQuery().use { rows -> rows.collect(map) }
                }
            }
        } catch (failure: SQLException) {
            // A cancelled scope — a collapsed node, a closed window — is cancellation,
            // not a database error.
            coroutineContext.ensureActive()
            val error = PostgresErrors.classify(failure, redaction)
            log.debug("catalog read failed: {}", error.code)
            throw DbException(error, failure)
        }
    }

    private fun PreparedStatement.configure(parameters: Array<out String>) {
        queryTimeout = this@PostgresCatalog.queryTimeout.inWholeSeconds.toInt()
        // maxRows is what stops the driver buffering a pathological catalog into heap.
        // With it in place these reads need no cursor and no transaction of their own.
        maxRows = limits.items + 1
        parameters.forEachIndexed { index, value -> setString(index + 1, value) }
    }

    private fun <T> ResultSet.collect(map: (ResultSet) -> T): Listing<T> {
        val items = ArrayList<T>()
        while (next()) {
            if (items.size == limits.items) return Listing(items, truncated = true)
            items += map(this)
        }
        return Listing(items)
    }

    /**
     * Rejects a name that cannot identify anything.
     *
     * A blank name means a bug upstream, and a NUL is the one character a PostgreSQL
     * identifier cannot hold — the driver would send a string the server reads as
     * ending early. Both stop here, as a classified error, rather than as a listing
     * that is quietly empty. Spaces, quotes, and mixed case are all legal in a name
     * and are none of this function's business; they are handled by passing the name
     * as a parameter and quoting it on the way back out.
     */
    private fun String.requireName() {
        if (isBlank() || contains('\u0000')) {
            throw DbException(DbError.QueryFailed("That is not a name any object can have."))
        }
    }

    private companion object {
        /**
         * `has_schema_privilege` answers for the *current* role, which is the one
         * whose queries will fail — not the owner in the column beside it. It is asked
         * here rather than per schema on demand because it is one more column on a
         * listing that is already being read, and the alternative is a round trip at
         * the moment the user opens an empty schema and wants an answer.
         *
         * `nspname LIKE 'pg\_%'` covers `pg_catalog`, `pg_toast`, and every
         * `pg_temp_N`/`pg_toast_temp_N` pair a busy server has accumulated. The
         * backslash is LIKE's escape, so the underscore is an underscore and not "any
         * character" — otherwise a schema named `pgx` would count as a system schema.
         */
        const val SCHEMA_SELECT = """
            SELECT n.nspname,
                   pg_catalog.pg_get_userbyid(n.nspowner),
                   pg_catalog.has_schema_privilege(n.oid, 'USAGE')
              FROM pg_catalog.pg_namespace n
        """

        const val ALL_SCHEMAS = "$SCHEMA_SELECT ORDER BY 1"

        const val USER_SCHEMAS = """
            $SCHEMA_SELECT
             WHERE n.nspname <> 'information_schema'
               AND n.nspname NOT LIKE 'pg\_%'
             ORDER BY 1
        """

        /**
         * `reltuples` is the planner's estimate, and it is `-1` on a relation that has
         * never been analyzed. Before PostgreSQL 14 it was `0` there, which an empty
         * table also is, so a zero estimate is reported as zero and the UI is careful
         * to call every one of these numbers an estimate.
         */
        const val RELATIONS = """
            SELECT c.relname,
                   CASE WHEN c.reltuples >= 0 THEN c.reltuples::bigint END
              FROM pg_catalog.pg_class c
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = ?
               AND c.relkind::text = ANY (string_to_array(?, ','))
             ORDER BY 1
        """

        /**
         * `prokind = 'f'` is a plain function: not an aggregate, not a window
         * function, and not a procedure, none of which are called the way a function
         * is. The column arrived in PostgreSQL 11.
         */
        const val FUNCTIONS = """
            SELECT p.proname,
                   pg_catalog.pg_get_function_identity_arguments(p.oid)
              FROM pg_catalog.pg_proc p
              JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = ?
               AND p.prokind = 'f'
             ORDER BY 1, 2
        """

        /**
         * `attnum > 0` drops the system columns, and `attisdropped` drops the
         * tombstone a dropped column leaves behind — which keeps its ordinal forever,
         * so the ordinals a table shows are not always `1..n`.
         *
         * The key test reads `pg_constraint` rather than `pg_index.indkey`: `conkey` is
         * a real `smallint[]`, so `= ANY` means what it appears to mean.
         */
        const val COLUMNS = """
            SELECT a.attnum,
                   a.attname,
                   pg_catalog.format_type(a.atttypid, a.atttypmod),
                   NOT a.attnotnull,
                   pg_catalog.pg_get_expr(d.adbin, d.adrelid),
                   EXISTS (
                       SELECT 1
                         FROM pg_catalog.pg_constraint k
                        WHERE k.conrelid = c.oid
                          AND k.contype = 'p'
                          AND a.attnum = ANY (k.conkey)
                   )
              FROM pg_catalog.pg_attribute a
              JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
              LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE n.nspname = ?
               AND c.relname = ?
               AND a.attnum > 0
               AND NOT a.attisdropped
             ORDER BY 1
        """

        /**
         * The `pg_class.relkind` values behind each kind, as a fixed allowlist. A
         * partitioned table (`p`) is a table: the user asked which tables they have,
         * and whether one of them is partitioned is not a different answer.
         */
        val ObjectKind.relkinds: String
            get() = when (this) {
                ObjectKind.TABLE -> "r,p"
                ObjectKind.VIEW -> "v"
                ObjectKind.MATERIALIZED_VIEW -> "m"
                // Functions are not in pg_class at all; they have their own query.
                ObjectKind.FUNCTION -> ""
            }

        fun isSystem(name: String): Boolean = name == "information_schema" || name.startsWith("pg_")
    }
}
