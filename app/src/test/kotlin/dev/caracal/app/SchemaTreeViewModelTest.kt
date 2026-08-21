package dev.caracal.app

import dev.caracal.core.catalog.ColumnInfo
import dev.caracal.core.catalog.Listing
import dev.caracal.core.catalog.ObjectKind
import dev.caracal.core.catalog.SchemaInfo
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.vault.VaultState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What the object browser asks the server, and when.
 *
 * "When" is most of it: the milestone's rule is that nothing loads until it is
 * opened, that what has been opened is not read again for no reason, and that a
 * failure lands on one node instead of on the tree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchemaTreeViewModelTest {
    private val id = ConnectionId("id-1")

    private fun service() = FakeConnectionService(VaultState.UNLOCKED).apply {
        schemas = Listing(listOf(SchemaInfo("public", owner = "caracal"), SchemaInfo("sales")))
    }

    private fun TestScope.model(service: FakeConnectionService) = SchemaTreeViewModel(service, this)

    /** The catalog reads made so far, without the connection bookkeeping around them. */
    private fun FakeConnectionService.catalogCalls() =
        calls.filter { it.startsWith("schemas") || it.startsWith("objects") || it.startsWith("columns") }

    @Test
    fun `opening a connection reads the schemas and nothing else`() = runTest {
        val service = service()
        val model = model(service)

        model.show(id)
        advanceUntilIdle()

        assertEquals(listOf("schemas(system=false)"), service.catalogCalls())
        assertEquals(listOf("public", "sales"), model.rows.map { it.label })
    }

    @Test
    fun `a schema's objects are read when the schema is opened, and not before`() = runTest {
        val service = service()
        service.seedObject("public", "users")
        val model = model(service)
        model.show(id)
        advanceUntilIdle()

        model.toggle(NodeKey.Schema("public"))
        advanceUntilIdle()

        // One query per kind, for the schema that was opened and no other.
        assertEquals(
            ObjectKind.entries.map { "objects(public, $it)" }.toSet(),
            service.catalogCalls().drop(1).toSet(),
        )
        assertTrue(model.rows.any { it.label == "Tables" })
        // The folder is listed; the table inside it waits for the folder to be opened.
        assertTrue(model.rows.none { it.label == "users" })
    }

    @Test
    fun `columns are read when an object is opened`() = runTest {
        val service = service()
        service.seedObject(
            schema = "public",
            name = "users",
            columns = listOf(ColumnInfo(1, "id", "bigint", nullable = false, primaryKey = true)),
        )
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        model.toggle(NodeKey.Schema("public"))
        advanceUntilIdle()

        model.toggle(NodeKey.Folder("public", ObjectKind.TABLE))
        advanceUntilIdle()
        assertTrue(model.rows.any { it.label == "users" })
        assertTrue(service.catalogCalls().none { it.startsWith("columns") })

        model.toggle(NodeKey.Relation("public", "users", ObjectKind.TABLE))
        advanceUntilIdle()

        assertTrue(service.catalogCalls().contains("columns(public, users)"))
        val column = model.rows.single { it.label == "id" }
        assertEquals("bigint", column.detail)
        assertEquals(listOf("PK", "not null"), column.flags)
    }

    @Test
    fun `re-opening a node uses what was already read`() = runTest {
        val service = service()
        service.seedObject("public", "users")
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        val folder = NodeKey.Folder("public", ObjectKind.TABLE)
        model.toggle(NodeKey.Schema("public"))
        model.toggle(folder)
        advanceUntilIdle()
        val readsSoFar = service.catalogCalls().size

        model.toggle(folder)
        model.toggle(folder)
        advanceUntilIdle()

        assertEquals(readsSoFar, service.catalogCalls().size)
        assertTrue(model.rows.any { it.label == "users" })
    }

    @Test
    fun `refreshing a node re-reads it and keeps the tree open`() = runTest {
        val service = service()
        service.seedObject("public", "users")
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        val folder = NodeKey.Folder("public", ObjectKind.TABLE)
        model.toggle(NodeKey.Schema("public"))
        model.toggle(folder)
        advanceUntilIdle()

        service.objects[("public" to ObjectKind.TABLE)] = Listing(emptyList())
        model.refresh(folder)
        advanceUntilIdle()

        assertEquals(2, service.catalogCalls().count { it == "objects(public, TABLE)" })
        // Still expanded, and now showing what the server says today.
        assertTrue(model.isExpanded(folder))
        assertTrue(model.rows.none { it.label == "users" })
    }

    @Test
    fun `refreshing the connection re-reads the schemas and everything still open`() = runTest {
        val service = service()
        service.seedObject("public", "users")
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        model.toggle(NodeKey.Schema("public"))
        model.toggle(NodeKey.Folder("public", ObjectKind.TABLE))
        advanceUntilIdle()

        model.refresh()
        advanceUntilIdle()

        assertEquals(2, service.catalogCalls().count { it == "schemas(system=false)" })
        assertEquals(2, service.catalogCalls().count { it == "objects(public, TABLE)" })
        assertTrue(model.rows.any { it.label == "users" })
    }

    @Test
    fun `a node that cannot be read reports on itself and leaves the tree standing`() = runTest {
        val service = service()
        service.seedObject("sales", "orders")
        service.failingSchema = "public"
        val model = model(service)
        model.show(id)
        advanceUntilIdle()

        model.toggle(NodeKey.Schema("public"))
        model.toggle(NodeKey.Schema("sales"))
        model.toggle(NodeKey.Folder("sales", ObjectKind.TABLE))
        advanceUntilIdle()

        // Every kind failed the same way, so the schema says it once rather than
        // repeating the same sentence under Tables, Views, and both the others.
        val failed = model.rows.single { it.key == NodeKey.Schema("public") }
        assertTrue(failed.error.orEmpty().contains("permission denied"))
        assertEquals(1, model.rows.count { it.error != null })
        // The schema list survived, and so did the schema that reads fine.
        assertIs<NodeState.Ready<*>>(model.root)
        assertTrue(model.rows.any { it.label == "orders" })
    }

    @Test
    fun `the system toggle re-reads only the schema list`() = runTest {
        val service = service()
        service.schemas = Listing(
            listOf(SchemaInfo("public"), SchemaInfo("pg_catalog", system = true)),
        )
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        assertEquals(listOf("public"), model.rows.map { it.label })

        model.toggleSystemSchemas()
        advanceUntilIdle()

        assertTrue(model.showSystemSchemas)
        assertEquals(listOf("public", "pg_catalog"), model.rows.map { it.label })
        assertEquals(listOf("system"), model.rows.single { it.label == "pg_catalog" }.flags)
    }

    @Test
    fun `being shown the connection it is already showing changes nothing`() = runTest {
        val service = service()
        service.seedObject("public", "users")
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        model.toggle(NodeKey.Schema("public"))
        model.toggle(NodeKey.Folder("public", ObjectKind.TABLE))
        advanceUntilIdle()
        val readsSoFar = service.catalogCalls().size

        // What the workspace does on every recomposition, and after every query.
        model.show(id)
        advanceUntilIdle()

        assertEquals(readsSoFar, service.catalogCalls().size)
        assertTrue(model.rows.any { it.label == "users" })
    }

    @Test
    fun `switching connections forgets the previous server entirely`() = runTest {
        val service = service()
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        model.toggle(NodeKey.Schema("public"))
        advanceUntilIdle()

        model.show(ConnectionId("id-2"))
        advanceUntilIdle()

        assertTrue(!model.isExpanded(NodeKey.Schema("public")))
        assertEquals(2, service.catalogCalls().count { it == "schemas(system=false)" })
    }

    @Test
    fun `locking empties the tree`() = runTest {
        val service = service()
        val model = model(service)
        model.show(id)
        advanceUntilIdle()

        model.clear()

        assertNull(model.root)
        assertNull(model.connectionId)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `a name is offered back as quoted SQL`() = runTest {
        val service = service()
        service.schemas = Listing(listOf(SchemaInfo("Sales")))
        service.seedObject("Sales", "Order Items")
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        model.toggle(NodeKey.Schema("Sales"))
        model.toggle(NodeKey.Folder("Sales", ObjectKind.TABLE))
        advanceUntilIdle()

        assertEquals("\"Sales\"", model.rows.first().identifier)
        assertEquals(
            "\"Sales\".\"Order Items\"",
            model.rows.single { it.label == "Order Items" }.identifier,
        )
    }

    @Test
    fun `a re-read keeps the previous listing on screen and says it is reloading`() = runTest {
        // §4.7: refreshing is not initial loading. The first read has nothing to show;
        // the second has the answer from the first, which is very nearly right, and
        // blanking the pane to a spinner throws away the user's place in it.
        val service = service()
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        assertEquals(listOf("public", "sales"), model.rows.map { it.label })

        service.gate = CompletableDeferred()
        model.refresh()

        assertTrue(model.reloading, "the header did not say the tree was being re-read")
        assertEquals(
            listOf("public", "sales"),
            model.rows.map { it.label },
            "the tree went blank while it was being refreshed",
        )

        service.gate?.complete(Unit)
        advanceUntilIdle()

        assertFalse(model.reloading)
        assertEquals(listOf("public", "sales"), model.rows.map { it.label })
    }

    @Test
    fun `a first read has nothing to keep and reports loading`() = runTest {
        val service = service()
        service.gate = CompletableDeferred()
        val model = model(service)

        model.show(id)

        assertIs<NodeState.Loading>(model.root)
        assertFalse(model.reloading, "a first read was reported as a refresh")

        service.gate?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `an empty schema says whether it is empty or merely closed to this role`() = runTest {
        val service = service()
        service.schemas = Listing(
            listOf(SchemaInfo("public"), SchemaInfo("walled", usable = false)),
        )
        val model = model(service)
        model.show(id)
        advanceUntilIdle()

        model.toggle(NodeKey.Schema("public"))
        model.toggle(NodeKey.Schema("walled"))
        advanceUntilIdle()

        val public = model.rows.single { it.key == NodeKey.Schema("public") }
        val walled = model.rows.single { it.key == NodeKey.Schema("walled") }

        assertEquals("This schema has no tables, views, or functions.", public.note)
        assertEquals(
            "This role has no USAGE on this schema, so nothing in it is visible.",
            walled.note,
        )
        // And it is flagged whether or not it is empty: every object under it would
        // refuse to be read, and the listing alone gives no hint of that.
        assertTrue("no access" in walled.flags)
        assertTrue("no access" !in public.flags)
    }

    @Test
    fun `a truncated listing says so on the node`() = runTest {
        val service = service()
        service.seedObject("public", "users")
        service.objects[("public" to ObjectKind.TABLE)] =
            Listing(service.objects.getValue("public" to ObjectKind.TABLE).items, truncated = true)
        val model = model(service)
        model.show(id)
        advanceUntilIdle()
        model.toggle(NodeKey.Schema("public"))
        model.toggle(NodeKey.Folder("public", ObjectKind.TABLE))
        advanceUntilIdle()

        val folder = model.rows.single { it.key == NodeKey.Folder("public", ObjectKind.TABLE) }
        assertEquals("Showing the first 1.", folder.note)
    }
}
