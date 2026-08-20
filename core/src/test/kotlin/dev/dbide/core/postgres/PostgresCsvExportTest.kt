package dev.dbide.core.postgres

import dev.dbide.core.export.ExportRefusal
import dev.dbide.core.result.DbError
import dev.dbide.core.result.DbException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The refusals an export makes on its own, asked without a database.
 *
 * Both are here rather than in the integration suite because both are about what
 * happens *instead of* querying: one refuses before a connection is taken, and
 * the other refuses a statement that ran and produced no result set to write. A
 * JDBC stack of proxies is enough to show either, and neither needs a server to
 * be true.
 */
class PostgresCsvExportTest {

    @Test
    fun `an ineligible statement is refused before a connection is taken`() {
        // The pool would refuse this write too, and that is the boundary. This is the
        // part that costs nothing and says why.
        val error = assertThrows<DbException> {
            runBlocking { PostgresAdapter(NoConnections).exportCsv("delete from invoices", StringBuilder()) }
        }.error

        assertIs<DbError.ExportUnavailable>(error)
        assertEquals(ExportRefusal.MODIFIES_DATA.message, error.message)
    }

    @Test
    fun `a statement that produces no result set is refused after it runs`() {
        // Nothing a read classifies as should reach this, which is exactly why it is
        // worth having: the alternative is a CSV holding a header and no rows, which
        // reads as an empty table rather than as a statement that had none to give.
        val out = StringBuilder()

        val error = assertThrows<DbException> {
            runBlocking { PostgresAdapter(rowlessDataSource()).exportCsv("select 1", out) }
        }.error

        assertIs<DbError.ExportUnavailable>(error)
        assertEquals(ExportRefusal.NO_ROWS.message, error.message)
        assertEquals("", out.toString(), "wrote a header for a result that does not exist")
    }

    /** Fails the test if anything asks it for a connection. */
    private val NoConnections: DataSource = jdbcProxy { method, _ ->
        if (method.name == "getConnection") throw AssertionError("took a connection for a refused statement")
        null
    }

    /**
     * A data source whose statements execute and return no rows.
     *
     * Proxies rather than a written-out stub: `PreparedStatement` has upwards of a
     * hundred methods, and this path uses six of them.
     */
    private fun rowlessDataSource(): DataSource {
        val statement: PreparedStatement = jdbcProxy { method, _ ->
            if (method.name == "execute") false else null
        }
        val connection: Connection = jdbcProxy { method, _ ->
            if (method.name == "prepareStatement") statement else null
        }
        return jdbcProxy { method, _ ->
            if (method.name == "getConnection") connection else null
        }
    }

    private inline fun <reified T> jdbcProxy(crossinline handle: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handle(method, args)
        } as T
}
