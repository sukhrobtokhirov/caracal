package dev.caracal.app

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.ConnectionId
import dev.caracal.core.connections.ConnectionSummary
import dev.caracal.core.connections.ConnectionView
import dev.caracal.core.connections.Engine
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.RuntimeState
import dev.caracal.core.connections.TlsMode
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** What the switcher shows for what has been typed into it. */
class ConnectionSearchTest {

    private fun view(name: String) = ConnectionView(
        summary = ConnectionSummary(
            config = ConnectionConfig(
                id = ConnectionId(name),
                name = name,
                engine = Engine.POSTGRES,
                host = "localhost",
                port = 5432,
                database = "caracal",
                username = "caracal",
                tlsMode = TlsMode.DISABLE,
                environment = Environment.DEV,
                readOnly = false,
                color = null,
                createdAt = Instant.parse("2026-08-21T10:00:00Z"),
            ),
            hasSecret = true,
        ),
        runtime = RuntimeState.CLOSED,
    )

    private val all = listOf(
        view("Production"),
        view("Staging"),
        view("Local PG"),
        view("analytics-replica"),
    )

    private fun names(query: String) = ConnectionSearch.match(all, query).map { it.config.name }

    @Test
    fun `nothing typed shows everything, in the order it was given`() {
        assertEquals(all.map { it.config.name }, names(""))
        // Whitespace is not a search term. A stray space must not empty the list.
        assertEquals(all.map { it.config.name }, names("   "))
    }

    @Test
    fun `matching ignores case, because nobody capitalises into a switcher`() {
        assertEquals(listOf("Production"), names("PROD"))
        assertEquals(listOf("Production"), names("prod"))
    }

    @Test
    fun `a name that starts with the term comes above one that merely contains it`() {
        // "Local PG" begins with it; "analytics-replica" has one in the middle. The
        // promotion is what makes three letters and Enter land where it is meant to.
        assertEquals(listOf("Local PG", "analytics-replica"), names("l"))
        // And it beats the given order: the two above it in the list still lose.
        assertEquals(listOf("analytics-replica", "Staging", "Local PG"), names("a"))
    }

    @Test
    fun `beyond the rank the given order is kept`() {
        // None of the three begins with "c", so none is promoted and the switcher
        // lists them in the order the sidebar behind it is showing.
        assertEquals(listOf("Production", "Local PG", "analytics-replica"), names("c"))
    }

    @Test
    fun `a term nothing is called matches nothing`() {
        assertEquals(emptyList(), names("mysql"))
    }
}
