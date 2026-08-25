package dev.caracal.app

import dev.caracal.core.connections.ConnectionDraft
import dev.caracal.core.connections.Environment
import dev.caracal.core.connections.RuntimeStatus
import dev.caracal.core.connections.SecretUpdate
import dev.caracal.core.connections.tlsModes
import dev.caracal.core.engines.Engines
import dev.caracal.engine.api.FormKeys
import dev.caracal.core.connections.ValidationError
import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.core.store.DuplicateNameException
import dev.caracal.core.vault.VaultState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {
    private fun service() = FakeConnectionService(VaultState.UNLOCKED)

    private fun TestScope.model(service: FakeConnectionService) = ConnectionsViewModel(service, this)

    private fun ConnectionsViewModel.fillValidForm(name: String = "Local") {
        val form = assertIs<Pane.Form>(pane).state
        // Chosen rather than assumed: the dialog opens on the first engine offered,
        // and which one that is depends on what the classpath holds.
        form.onEngine(assertNotNull(Engines.byId(POSTGRES)))
        form.onName(name)
        form.type(FormKeys.HOST, "localhost")
        form.type(FormKeys.DATABASE, "caracal")
        form.type(FormKeys.USER, "caracal")
        form.onSecret("hunter2")
    }

    @Test
    fun `the list is read on refresh, production first`() = runTest {
        val service = service()
        service.seed(name = "zeta-dev")
        service.seed(name = "alpha-prod", environment = Environment.PROD)
        val model = model(service)

        model.refresh()
        advanceUntilIdle()

        assertEquals(listOf("alpha-prod", "zeta-dev"), model.connections.map { it.config.name })
        assertFalse(model.busy)
    }

    @Test
    fun `an empty list is not an error`() = runTest {
        val model = model(service())

        model.refresh()
        advanceUntilIdle()

        assertEquals(emptyList(), model.connections)
        assertNull(model.failure)
    }

    @Test
    fun `creating a connection sends the typed draft and selects the result`() = runTest {
        val service = service()
        val model = model(service)

        model.startCreating()
        model.fillValidForm()
        model.save()
        advanceUntilIdle()

        val draft = service.drafts.single()
        assertEquals("Local", draft.name)
        assertEquals("localhost", draft.values[FormKeys.HOST])
        assertIs<SecretUpdate.Replace>(draft.secret)
        assertIs<Pane.Detail>(model.pane)
        assertEquals(listOf("Local"), model.connections.map { it.config.name })
    }

    @Test
    fun `a form the service would reject never reaches it`() = runTest {
        val service = service()
        val model = model(service)

        model.startCreating()
        // No name, and a host emptied of the default the form opened with: `:core`
        // would reject this, but so does the form.
        val empty = assertIs<Pane.Form>(model.pane).state
        empty.onEngine(assertNotNull(Engines.byId(POSTGRES)))
        empty.type(FormKeys.HOST, "")
        model.save()
        advanceUntilIdle()

        assertTrue(service.calls.isEmpty())
        val form = assertIs<Pane.Form>(model.pane).state
        assertEquals("A name is required.", form.errors[ValidationError.NAME])
        assertEquals("Host is required.", form.errors[FormKeys.HOST])
    }

    @Test
    fun `a duplicate name is reported without losing what was typed`() = runTest {
        val service = service()
        service.nextFailure = DuplicateNameException()
        val model = model(service)

        model.startCreating()
        model.fillValidForm(name = "Local")
        model.save()
        advanceUntilIdle()

        assertEquals("duplicate_name", assertNotNull(model.failure).code)
        val form = assertIs<Pane.Form>(model.pane).state
        assertEquals("Local", form.name)
        assertEquals("localhost", form.value(FormKeys.HOST))
    }

    @Test
    fun `a failed save keeps the non-secret fields so nothing has to be retyped`() = runTest {
        val service = service()
        service.nextFailure = DuplicateNameException()
        val model = model(service)
        model.startCreating()
        model.fillValidForm()
        model.save()
        advanceUntilIdle()

        val form = assertIs<Pane.Form>(model.pane).state
        assertEquals("caracal", form.value(FormKeys.DATABASE))
        assertEquals("caracal", form.value(FormKeys.USER))
    }

    @Test
    fun `saving twice in a row only creates one connection`() = runTest {
        val service = service()
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        val model = model(service)
        model.startCreating()
        model.fillValidForm()

        model.save()
        advanceUntilIdle()
        assertTrue(model.busy)

        model.save()
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, service.calls.count { it == "create" })
    }

    @Test
    fun `editing starts from the saved connection and leaves the password alone`() = runTest {
        val service = service()
        val saved = service.seed(name = "Local")
        val model = model(service)
        model.refresh()
        advanceUntilIdle()

        model.startEditing(saved)

        val form = assertIs<Pane.Form>(model.pane).state
        assertEquals("Local", form.name)
        assertTrue(form.isEditing)
        assertFalse(form.replaceSecret, "an edit must not begin by replacing the password")
        assertEquals("", form.secret, "the saved password is never prefilled")
    }

    @Test
    fun `an edit that changes only a colour sends an unchanged secret`() = runTest {
        val service = service()
        val saved = service.seed(name = "Local")
        val model = model(service)
        model.refresh()
        advanceUntilIdle()

        model.startEditing(saved)
        assertIs<Pane.Form>(model.pane).state.onColor("#ff8800")
        model.save()
        advanceUntilIdle()

        val draft = service.drafts.single()
        assertEquals(SecretUpdate.Unchanged, draft.secret)
        assertEquals("#ff8800", draft.color)
        assertTrue(model.connections.single().hasSecret, "the saved password survived the edit")
    }

    @Test
    fun `choosing to replace the password and typing nothing clears it`() = runTest {
        val service = service()
        val saved = service.seed(name = "Local")
        val model = model(service)
        model.refresh()
        advanceUntilIdle()

        model.startEditing(saved)
        assertIs<Pane.Form>(model.pane).state.onReplaceSecret(true)
        model.save()
        advanceUntilIdle()

        assertEquals(SecretUpdate.Clear, service.drafts.single().secret)
        assertFalse(model.connections.single().hasSecret)
    }

    @Test
    fun `cancelling an edit returns to the connection rather than to nothing`() = runTest {
        val service = service()
        val saved = service.seed()
        val model = model(service)
        model.refresh()
        advanceUntilIdle()
        model.startEditing(saved)

        model.cancelForm()

        assertEquals(Pane.Detail(saved.id), model.pane)
    }

    @Test
    fun `cancelling a new connection returns to nothing selected`() = runTest {
        val model = model(service())
        model.startCreating()

        model.cancelForm()

        assertEquals(Pane.Empty, model.pane)
    }

    @Test
    fun `a successful test is reported with what it reached`() = runTest {
        val service = service()
        val saved = service.seed()
        val model = model(service)
        model.refresh()
        advanceUntilIdle()

        model.test(saved.id)
        advanceUntilIdle()

        val result = assertNotNull(model.testResult)
        assertEquals(POSTGRES, result.engineId)
        assertEquals("16.2", result.serverVersion)
        assertNull(model.failure)
    }

    @Test
    fun `a failed test is a classified message, not a crash`() = runTest {
        val service = service()
        val saved = service.seed()
        val model = model(service)
        model.refresh()
        advanceUntilIdle()

        // Armed after the refresh, so it is the test call that fails and not the list.
        service.nextFailure = DbException(DbError.AuthenticationFailed())
        model.test(saved.id)
        advanceUntilIdle()

        assertEquals("authentication_failed", assertNotNull(model.failure).code)
        assertNull(model.testResult)
    }

    @Test
    fun `opening and closing move the status the list shows`() = runTest {
        val service = service()
        val saved = service.seed()
        val model = model(service)
        model.refresh()
        advanceUntilIdle()

        model.open(saved.id)
        advanceUntilIdle()
        assertEquals(RuntimeStatus.OPEN, model.connections.single().runtime.status)

        model.close(saved.id)
        advanceUntilIdle()
        assertEquals(RuntimeStatus.CLOSED, model.connections.single().runtime.status)
    }

    @Test
    fun `deleting asks first and does nothing until it is confirmed`() = runTest {
        val service = service()
        val saved = service.seed()
        val model = model(service)
        model.refresh()
        advanceUntilIdle()

        model.confirmDelete(saved)
        advanceUntilIdle()

        assertEquals(saved, model.pendingDelete)
        assertFalse("delete" in service.calls, "the connection was deleted without confirmation")

        model.cancelDelete()
        assertNull(model.pendingDelete)
        assertEquals(1, service.stored.size)
    }

    @Test
    fun `a confirmed delete removes the connection and clears the pane`() = runTest {
        val service = service()
        val saved = service.seed()
        val model = model(service)
        model.refresh()
        advanceUntilIdle()
        model.select(saved.id)
        model.confirmDelete(saved)

        model.delete(saved.id)
        advanceUntilIdle()

        assertEquals(emptyList(), model.connections)
        assertEquals(Pane.Empty, model.pane)
        assertNull(model.pendingDelete)
    }

    @Test
    fun `locking drops the list, because a locked application shows nothing`() = runTest {
        val service = service()
        service.seed()
        val model = model(service)
        model.refresh()
        advanceUntilIdle()

        model.clear()

        assertEquals(emptyList(), model.connections)
        assertEquals(Pane.Empty, model.pane)
        assertFalse(model.busy)
    }

    @Test
    fun `a test result is cleared by the next thing the user does`() = runTest {
        val service = service()
        val saved = service.seed()
        val model = model(service)
        model.refresh()
        advanceUntilIdle()
        model.test(saved.id)
        advanceUntilIdle()
        assertNotNull(model.testResult)

        model.open(saved.id)
        advanceUntilIdle()

        assertNull(model.testResult)
    }

    @Test
    fun `switching engines resets the fields that do not carry over`() = runTest {
        val model = model(service())
        model.startCreating()
        val form = assertIs<Pane.Form>(model.pane).state
        form.onEngine(assertNotNull(Engines.byId(POSTGRES)))
        form.type(FormKeys.DATABASE, "caracal")

        form.onEngine(assertNotNull(Engines.byId(REDIS)))

        assertEquals("6379", form.value(FormKeys.PORT))
        assertEquals("0", form.value(FormKeys.DATABASE))
        assertEquals(listOf("disable", "require"), form.engine.tlsModes.map { it.wire })
    }

    @Test
    fun `a port that is not a number is caught before the service sees it`() = runTest {
        val service = service()
        val model = model(service)
        model.startCreating()
        val form = assertIs<Pane.Form>(model.pane).state
        model.fillValidForm()

        // The field filters digits, so the only way in is a value set directly.
        form.type(FormKeys.PORT, "99999")
        model.save()
        advanceUntilIdle()

        assertTrue(service.calls.isEmpty())
        assertEquals("Port must be between 1 and 65535.", form.errors[FormKeys.PORT])
    }

    @Test
    fun `an empty port field means the engine default, not a missing value`() = runTest {
        val service = service()
        val model = model(service)
        model.startCreating()
        model.fillValidForm()

        model.save()
        advanceUntilIdle()

        assertEquals("5432", service.drafts.single().values[FormKeys.PORT])
    }

    @Test
    fun `the selected connection follows the list as it is refreshed`() = runTest {
        val service = service()
        val saved = service.seed(name = "Local")
        val model = model(service)
        model.refresh()
        advanceUntilIdle()
        model.select(saved.id)

        model.open(saved.id)
        advanceUntilIdle()

        assertEquals(RuntimeStatus.OPEN, assertNotNull(model.selected).runtime.status)
    }

    @Test
    fun `a draft built from an existing connection carries its identity`() {
        val service = service()
        val saved = service.seed(name = "Local", environment = Environment.PROD, readOnly = true)

        val draft = ConnectionDraft.of(saved.config)

        assertEquals(Environment.PROD, draft.environment)
        assertTrue(draft.readOnly)
    }
}
