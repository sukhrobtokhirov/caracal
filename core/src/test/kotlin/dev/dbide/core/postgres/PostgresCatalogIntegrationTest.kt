package dev.dbide.core.postgres

import com.zaxxer.hikari.HikariDataSource
import dev.dbide.core.catalog.CatalogLimits
import dev.dbide.core.catalog.ColumnInfo
import dev.dbide.core.catalog.ObjectKind
import dev.dbide.core.connections.Secret
import dev.dbide.core.sql.Identifiers
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Whether the object browser describes the database that is actually there.
 *
 * Asked of a real server because every interesting answer is one `pg_catalog`
 * knows and no schema of ours does: what a dropped column leaves behind, what
 * `format_type` calls a `varchar(20)`, which relkind a partitioned table has, and
 * whether a name with a capital letter and a space in it survives the round trip
 * from catalog to tree to SQL.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DBIDE_INTEGRATION", matches = "1")
class PostgresCatalogIntegrationTest {

    // --- Schemas --------------------------------------------------------------

    @Test
    fun `user schemas are listed and the server's own are not`() = runBlocking {
        val names = catalog().schemas().items.map { it.name }

        assertTrue("Sales" in names)
        assertTrue("public" in names)
        assertTrue(names.none { it.startsWith("pg_") }, "system schemas leaked: $names")
        assertTrue("information_schema" !in names)
    }

    @Test
    fun `system schemas appear when they are asked for, and say that is what they are`() =
        runBlocking {
            val schemas = catalog().schemas(includeSystem = true)

            val catalogSchema = schemas.items.single { it.name == "pg_catalog" }
            assertTrue(catalogSchema.system)
            assertTrue(schemas.items.single { it.name == "information_schema" }.system)
            // A user schema is not reclassified by turning the toggle on.
            assertTrue(!schemas.items.single { it.name == "Sales" }.system)
        }

    @Test
    fun `a schema carries its owner`() = runBlocking {
        assertEquals(postgres.username, catalog().schemas().items.single { it.name == "Sales" }.owner)
    }

    // --- Objects --------------------------------------------------------------

    @Test
    fun `tables include partitioned ones, and exclude everything that is not a table`() =
        runBlocking {
            val tables = catalog().objects("Sales", ObjectKind.TABLE).items.map { it.name }

            // "events" is partitioned: relkind 'p', and still one table to the user.
            // Compared as a set, because the order is the server's collation's business.
            assertEquals(setOf("Order Items", "events", "twin"), tables.toSet())
        }

    @Test
    fun `views, materialized views, and functions are each their own kind`() = runBlocking {
        val catalog = catalog()

        assertEquals(listOf("recent"), catalog.objects("Sales", ObjectKind.VIEW).items.map { it.name })
        assertEquals(
            listOf("totals"),
            catalog.objects("Sales", ObjectKind.MATERIALIZED_VIEW).items.map { it.name },
        )

        val function = catalog.objects("Sales", ObjectKind.FUNCTION).items.single()
        assertEquals("discount", function.name)
        // Name and signature is all v0.1 shows, and the signature is what tells two
        // overloads of the same name apart — with the parameter names the function
        // was declared with, which is what a caller has to write.
        assertEquals("amount numeric, rate numeric", function.signature)
    }

    @Test
    fun `an analyzed table carries the planner's row estimate`() = runBlocking {
        val table = catalog().objects("Sales", ObjectKind.TABLE).items.single { it.name == "Order Items" }

        assertEquals(3L, table.rowEstimate)
    }

    @Test
    fun `a table nobody has analyzed has no estimate rather than a made-up one`() = runBlocking {
        val table = catalog().objects("Sales", ObjectKind.TABLE).items.single { it.name == "events" }

        assertNull(table.rowEstimate)
    }

    @Test
    fun `a schema that does not exist is empty, not an error`() = runBlocking {
        assertTrue(catalog().objects("no_such_schema", ObjectKind.TABLE).items.isEmpty())
    }

    @Test
    fun `a listing past the bound says so`() = runBlocking {
        val listing = catalog(CatalogLimits(items = 1)).objects("Sales", ObjectKind.TABLE)

        assertEquals(1, listing.items.size)
        assertTrue(listing.truncated)
    }

    // --- Columns --------------------------------------------------------------

    @Test
    fun `columns arrive in declaration order with the types a person would write`() = runBlocking {
        val columns = catalog().columns("Sales", "Order Items")

        assertEquals(listOf("Id", "Item Name", "price", "note"), columns.map { it.name })
        assertEquals(listOf(1, 2, 3, 4), columns.map { it.ordinal })
        assertEquals(
            listOf("bigint", "character varying(20)", "numeric(10,2)", "text"),
            columns.map { it.typeName },
        )
    }

    @Test
    fun `the key, the not-null, and the default are each reported`() = runBlocking {
        val columns = catalog().columns("Sales", "Order Items").associateBy { it.name }

        val id = columns.getValue("Id")
        assertTrue(id.primaryKey)
        assertTrue(!id.nullable)
        // bigserial is a bigint with a sequence default, and saying so is the point.
        assertTrue(id.default.orEmpty().startsWith("nextval("), "unexpected default ${id.default}")

        assertTrue(!columns.getValue("Item Name").nullable)
        assertTrue(!columns.getValue("Item Name").primaryKey)
        assertEquals("0.00", columns.getValue("price").default)
        assertTrue(columns.getValue("note").nullable)
        assertNull(columns.getValue("note").default)
    }

    @Test
    fun `a dropped column leaves no ghost, and does not renumber the ones that remain`() =
        runBlocking {
            val columns = catalog().columns("public", "gappy")

            assertEquals(listOf("first", "third"), columns.map { it.name })
            // PostgreSQL keeps the dropped column's attnum forever, so the ordinals a
            // table reports are not always 1..n. Renumbering them here would be a lie
            // that only shows up when something else joins on the ordinal.
            assertEquals(listOf(1, 3), columns.map { it.ordinal })
        }

    @Test
    fun `a view has columns too`() = runBlocking {
        assertEquals(listOf("Id"), catalog().columns("Sales", "recent").map { it.name })
    }

    @Test
    fun `the columns of a relation in another schema are not mixed in`() = runBlocking {
        // Same table name in two schemas: a query that forgot the namespace join
        // would return both, and the tree would show a table with twice its columns.
        assertEquals(listOf("id"), catalog().columns("public", "twin").map(ColumnInfo::name))
        assertEquals(listOf("other"), catalog().columns("Sales", "twin").map(ColumnInfo::name))
    }

    // --- Back into SQL --------------------------------------------------------

    @Test
    fun `the identifier the tree inserts selects the object it names`() = runBlocking {
        val table = catalog().objects("Sales", ObjectKind.TABLE).items.single { it.name == "Order Items" }
        val column = catalog().columns("Sales", "Order Items").first { it.name == "Item Name" }

        val sql = "SELECT ${Identifiers.quote(column.name)} " +
            "FROM ${Identifiers.qualify(table.schema, table.name)}"
        val result = PostgresAdapter(dataSource).execute(sql)

        assertEquals("Item Name", result.columns.single().name)
        assertEquals(3, result.rows.size)
    }

    private fun catalog(limits: CatalogLimits = CatalogLimits()) =
        PostgresCatalog(dataSource, limits = limits)

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        private lateinit var dataSource: HikariDataSource

        @JvmStatic
        @BeforeAll
        fun seed() {
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    // Mixed case and a space, so every name here has to be quoted to be
                    // used and has to survive unquoted to be matched.
                    statement.execute("""CREATE SCHEMA "Sales"""")
                    statement.execute(
                        """
                        CREATE TABLE "Sales"."Order Items" (
                            "Id" bigserial PRIMARY KEY,
                            "Item Name" varchar(20) NOT NULL,
                            price numeric(10,2) DEFAULT 0.00,
                            note text
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """INSERT INTO "Sales"."Order Items" ("Item Name") VALUES ('a'), ('b'), ('c')""",
                    )
                    statement.execute("""ANALYZE "Sales"."Order Items"""")
                    statement.execute("""CREATE VIEW "Sales".recent AS SELECT "Id" FROM "Sales"."Order Items"""")
                    statement.execute(
                        """CREATE MATERIALIZED VIEW "Sales".totals AS SELECT count(*) AS n FROM "Sales"."Order Items"""",
                    )
                    statement.execute(
                        """
                        CREATE FUNCTION "Sales".discount(amount numeric, rate numeric)
                        RETURNS numeric LANGUAGE sql AS 'SELECT amount * rate'
                        """.trimIndent(),
                    )
                    statement.execute("""CREATE TABLE "Sales".events (id int, at date) PARTITION BY RANGE (at)""")
                    statement.execute("""CREATE TABLE "Sales".twin (other text)""")

                    statement.execute("CREATE TABLE twin (id int)")
                    statement.execute("CREATE TABLE gappy (first int, second int, third int)")
                    statement.execute("ALTER TABLE gappy DROP COLUMN second")
                }
            }
            dataSource = PostgresDataSources.create(
                PostgresConnectionConfig(
                    host = postgres.host,
                    port = postgres.firstMappedPort,
                    database = postgres.databaseName,
                    user = postgres.username,
                    password = Secret(postgres.password),
                ),
            )
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            dataSource.close()
            postgres.stop()
        }
    }
}
