package dev.dbide.core.sql

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * The names the object browser writes back into SQL.
 *
 * The reason to test something this small is that the failure is silent: an
 * identifier quoted wrongly does not throw, it selects from a different table.
 */
class IdentifiersTest {

    @Test
    fun `an ordinary name is still quoted`() {
        // Quoting everything is the point. An unquoted `users` is folded to lower
        // case, which is the same name here and a different one the moment the table
        // was created as "Users".
        assertEquals("\"users\"", Identifiers.quote("users"))
    }

    @Test
    fun `case is preserved, because unquoted case is not`() {
        assertEquals("\"Order Items\"", Identifiers.quote("Order Items"))
        assertEquals("\"USERS\"", Identifiers.quote("USERS"))
    }

    @Test
    fun `an embedded double quote is doubled`() {
        // A legal, if hostile, table name. Quoting it without escaping produces SQL
        // that ends the identifier early and then fails — or worse, does not.
        assertEquals("\"say \"\"hi\"\"\"", Identifiers.quote("say \"hi\""))
    }

    @Test
    fun `a reserved word needs no special case, having been quoted like everything else`() {
        assertEquals("\"select\"", Identifiers.quote("select"))
        assertEquals("\"table\"", Identifiers.quote("table"))
    }

    @Test
    fun `a name that is not ASCII survives unchanged`() {
        assertEquals("\"поставщики\"", Identifiers.quote("поставщики"))
        assertEquals("\"naïve\"", Identifiers.quote("naïve"))
    }

    @Test
    fun `qualifying quotes both halves separately`() {
        assertEquals("\"Sales\".\"Order Items\"", Identifiers.qualify("Sales", "Order Items"))
        // The dot inside a name is part of the name, not a separator.
        assertEquals("\"a.b\".\"c\"", Identifiers.qualify("a.b", "c"))
    }
}
