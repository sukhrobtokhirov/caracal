package dev.caracal.engine.sqlite

import dev.caracal.core.result.DbException
import dev.caracal.core.text.Redaction
import dev.caracal.engine.api.CatalogLimits
import dev.caracal.engine.api.CatalogObject
import dev.caracal.engine.api.ColumnInfo
import dev.caracal.engine.api.Listing
import dev.caracal.engine.api.ObjectKind
import dev.caracal.engine.api.SchemaInfo
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
 * Reads the object tree out of `sqlite_schema`.
 *
 * SQLite's catalogue is one table per attached database holding the `CREATE`
 * statement of everything in it. That is a much smaller surface than `pg_catalog`, and
 * the two places it is smaller are worth stating rather than hiding, because a browser
 * that invented an answer would be lying about the file:
 *
 * - **There are no materialized views and no schema-scoped functions.** SQLite has
 *   neither, so both kinds list empty. Empty is the true answer; a `PRAGMA
 *   function_list` would produce a list of the *library's* built-ins, which belong to
 *   no database and would be shown identically under every file the user opens.
 * - **There is no row estimate.** SQLite keeps no planner statistic that is free to
 *   read, and `count(*)` is a full scan of the table the user has not asked to open
 *   yet. So the count is null rather than a number nobody should trust.
 *
 * What SQLite has instead is [schemas] meaning something unusual: `main`, `temp`, and
 * whatever the connection has `ATTACH`ed. The engine declares
 * [dev.caracal.engine.api.NamespaceModel.NONE] because nothing about *making* a
 * connection asks the user to choose one — but a tree still has to have a root, and
 * the attached databases are it.
 */
class SqliteCatalog(
    private val dataSource: DataSource,
    private val redaction: Redaction = Redaction.NONE,
    private val queryTimeout: Duration = 10.seconds,
    private val limits: CatalogLimits = CatalogLimits(),
) {
    private val log = LoggerFactory.getLogger(SqliteCatalog::class.java)

    /**
     * The databases this connection can see.
     *
     * [includeSystem] adds `temp`, which holds the tables a `CREATE TEMP TABLE` made
     * and is SQLite's own rather than the user's. `main` is always there, even for a
     * database with nothing in it, which is what makes a listing of size zero
     * impossible and a blank object tree a real answer rather than a failed one.
     */
    suspend fun schemas(includeSystem: Boolean = false): Listing<SchemaInfo> =
        list(DATABASES) { rows -> rows.getString(1) }
            .let { listing ->
                Listing(
                    items = listing.items
                        .filter { includeSystem || !isSystem(it) }
                        .map { SchemaInfo(name = it, owner = null, system = isSystem(it)) },
                    truncated = listing.truncated,
                )
            }

    /**
     * The objects of one [kind] in one attached database.
     *
     * The database name is written into the statement rather than bound, because it
     * is a *schema qualifier* — `main.sqlite_schema` — and SQL has no parameter for
     * one. So it goes through [quoted], which doubles the closing quote, and through
     * [requireName], which refuses anything a `PRAGMA database_list` would not have
     * produced. Today every name reaching here came from SQLite itself; the day that
     * stops being true is not the day to discover this built SQL by concatenation.
     */
    suspend fun objects(schema: String, kind: ObjectKind): Listing<CatalogObject> {
        schema.requireName()
        val type = when (kind) {
            ObjectKind.TABLE -> "table"
            ObjectKind.VIEW -> "view"
            // Neither exists in SQLite. Listing empty rather than refusing, because a
            // tree that asks every kind of every schema should get an empty branch and
            // not an error dialog.
            ObjectKind.MATERIALIZED_VIEW, ObjectKind.FUNCTION -> return Listing(emptyList())
        }
        return list(objectsIn(schema), type) { rows ->
            CatalogObject(
                schema = schema,
                name = rows.getString(1),
                kind = kind,
                // SQLite has no free row estimate, and a count is a full scan.
                rowEstimate = null,
            )
        }
    }

    /**
     * The columns of one table or view, in declaration order.
     *
     * `pragma_table_info` rather than `PRAGMA table_info(...)`, because the
     * table-valued form takes both the relation and the schema as **bound
     * parameters** — the only place in this file where a name does not have to be
     * pasted into the statement, and the reason it is worth the less familiar
     * spelling.
     *
     * Unbounded, like PostgreSQL's: SQLite caps a table at 2000 columns by default, so
     * the answer is small by construction and a truncation flag would only ever say
     * `false`.
     *
     * [ColumnInfo.typeName] is the *declared* type, verbatim — `DECIMAL(30,10)`,
     * `wibble`, or the empty string for a column declared with no type at all. That is
     * what the schema says and what a person reading the tree wants to see. It is
     * emphatically not a promise about what any row holds; see [SqliteValues].
     */
    suspend fun columns(schema: String, relation: String): List<ColumnInfo> {
        schema.requireName()
        return list(COLUMNS, relation, schema) { rows ->
            ColumnInfo(
                ordinal = rows.getInt(1) + 1,
                name = rows.getString(2),
                typeName = rows.getString(3).orEmpty(),
                nullable = !rows.getBoolean(4),
                default = rows.getString(5),
                primaryKey = rows.getInt(6) > 0,
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
                    statement.queryTimeout = queryTimeout.asQueryTimeoutSeconds()
                    parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                    statement.maxRows = limits.items + 1
                    statement.read(map)
                }
            }
        } catch (failure: SQLException) {
            coroutineContext.ensureActive()
            val error = SqliteErrors.classify(failure, redaction)
            log.debug("a catalog read failed: {}", error.code)
            throw DbException(error, failure)
        }
    }

    private fun <T> PreparedStatement.read(map: (ResultSet) -> T): Listing<T> {
        val items = ArrayList<T>()
        executeQuery().use { rows ->
            while (rows.next()) {
                if (items.size == limits.items) return Listing(items, truncated = true)
                items += map(rows)
            }
        }
        return Listing(items)
    }

    /** `temp` is SQLite's, not the user's. Everything attached by name is the user's. */
    private fun isSystem(name: String): Boolean = name.equals("temp", ignoreCase = true)

    /**
     * The statement that lists one database's objects, with the database name in it.
     *
     * Built per call because the qualifier cannot be bound. The *type* still is, which
     * is the half that would otherwise be an allowlist to get wrong.
     */
    private fun objectsIn(schema: String): String =
        """
        SELECT name FROM ${quoted(schema)}.sqlite_schema
        WHERE type = ? AND name IS NOT NULL
        ORDER BY name
        """.trimIndent()

    private fun quoted(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /**
     * Refuses a name that could not have come from this server.
     *
     * The same guard `PostgresCatalog` has, and it is worth keeping even though
     * [quoted] already escapes: quoting is the defence and this is the assertion that
     * the defence was never needed. A name with a newline or a null in it did not come
     * out of `PRAGMA database_list`, and something has gone wrong further up.
     */
    private fun String.requireName() {
        require(isNotBlank() && length <= MAX_NAME && none { it.isISOControl() }) {
            "not a database name"
        }
    }

    private companion object {
        /**
         * Every attached database, `main` first.
         *
         * `PRAGMA database_list` rather than a table, because the attachments are a
         * property of the connection and not of the file. Read through the
         * table-valued form so it can go through the same bounded reader as the rest.
         */
        const val DATABASES = "SELECT name FROM pragma_database_list ORDER BY seq"

        /**
         * One relation's columns, with both the relation and the schema bound.
         *
         * `notnull` is quoted because it is a keyword; `cid` is 0-based and
         * [ColumnInfo.ordinal] is 1-based, which is why it is not simply copied.
         */
        const val COLUMNS =
            """
            SELECT cid, name, type, "notnull", dflt_value, pk
            FROM pragma_table_info(?, ?)
            ORDER BY cid
            """

        const val MAX_NAME = 255
    }
}
