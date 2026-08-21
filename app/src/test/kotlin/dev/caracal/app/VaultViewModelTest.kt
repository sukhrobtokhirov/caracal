package dev.caracal.app

import dev.caracal.core.store.StoreOpenException
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
class VaultViewModelTest {
    private fun viewModel(
        service: FakeConnectionService,
        scope: TestScope,
        onUnlocked: () -> Unit = {},
    ) = VaultViewModel(service, scope, onUnlocked)

    @Test
    fun `a fresh installation shows the setup screen`() = runTest {
        val model = viewModel(FakeConnectionService(VaultState.SETUP_REQUIRED), TestScope(testScheduler))

        model.load()
        advanceUntilIdle()

        assertEquals(VaultUiState.Setup, model.screen)
    }

    @Test
    fun `a returning user shows the unlock screen`() = runTest {
        val model = viewModel(FakeConnectionService(VaultState.LOCKED), TestScope(testScheduler))

        model.load()
        advanceUntilIdle()

        assertEquals(VaultUiState.Locked, model.screen)
    }

    @Test
    fun `setup will not submit until the password is long enough and confirmed`() = runTest {
        val model = viewModel(FakeConnectionService(), TestScope(testScheduler))
        model.load()
        advanceUntilIdle()

        assertFalse(model.canSubmit)

        model.onPasswordChange("short")
        assertFalse(model.canSubmit)
        assertEquals("Use at least ${model.minimumPasswordLength} characters.", model.setupHint)

        model.onPasswordChange("long-enough-password")
        assertFalse(model.canSubmit, "a password with no confirmation is not submittable")

        model.onConfirmationChange("long-enough-passwort")
        assertFalse(model.canSubmit)
        assertEquals("The two passwords do not match.", model.setupHint)

        model.onConfirmationChange("long-enough-password")
        assertTrue(model.canSubmit)
        assertNull(model.setupHint)
    }

    @Test
    fun `unlocking needs only a password, not a confirmation`() = runTest {
        val model = viewModel(FakeConnectionService(VaultState.LOCKED), TestScope(testScheduler))
        model.load()
        advanceUntilIdle()

        model.onPasswordChange("x")

        assertTrue(model.canSubmit)
        assertNull(model.setupHint)
    }

    @Test
    fun `choosing a master password unlocks the application and announces it`() = runTest {
        val service = FakeConnectionService()
        var unlocked = false
        val model = viewModel(service, TestScope(testScheduler)) { unlocked = true }
        model.load()
        advanceUntilIdle()

        model.onPasswordChange("correct-horse")
        model.onConfirmationChange("correct-horse")
        model.submit()
        advanceUntilIdle()

        assertEquals(VaultUiState.Unlocked, model.screen)
        assertTrue(unlocked)
        assertEquals(listOf("setUp"), service.calls)
    }

    @Test
    fun `the password fields are emptied once they have been used`() = runTest {
        val model = viewModel(FakeConnectionService(), TestScope(testScheduler))
        model.load()
        advanceUntilIdle()

        model.onPasswordChange("correct-horse")
        model.onConfirmationChange("correct-horse")
        model.submit()
        advanceUntilIdle()

        assertEquals("", model.password)
        assertEquals("", model.confirmation)
    }

    @Test
    fun `a wrong master password reports one generic message and stays locked`() = runTest {
        val model = viewModel(FakeConnectionService(VaultState.LOCKED), TestScope(testScheduler))
        model.load()
        advanceUntilIdle()

        model.onPasswordChange("not-the-password")
        model.submit()
        advanceUntilIdle()

        assertEquals(VaultUiState.Locked, model.screen)
        val failure = assertNotNull(model.failure)
        assertEquals("wrong_password", failure.code)
        assertEquals("The master password is incorrect.", failure.message)
    }

    @Test
    fun `typing again clears the previous failure`() = runTest {
        val model = viewModel(FakeConnectionService(VaultState.LOCKED), TestScope(testScheduler))
        model.load()
        advanceUntilIdle()
        model.onPasswordChange("not-the-password")
        model.submit()
        advanceUntilIdle()

        model.onPasswordChange("correct-horse")

        assertNull(model.failure)
    }

    @Test
    fun `the derivation is visible while it runs and cannot be submitted twice`() = runTest {
        val service = FakeConnectionService(VaultState.LOCKED)
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        val model = viewModel(service, TestScope(testScheduler))
        model.load()
        advanceUntilIdle()

        model.onPasswordChange("correct-horse")
        model.submit()
        advanceUntilIdle()

        assertTrue(model.busy)
        assertFalse(model.canSubmit)

        model.submit()
        advanceUntilIdle()
        assertEquals(listOf("unlock"), service.calls, "a second submit while busy started another unlock")

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.busy)
        assertEquals(VaultUiState.Unlocked, model.screen)
    }

    @Test
    fun `locking returns to the unlock screen and closes everything behind it`() = runTest {
        val service = FakeConnectionService(VaultState.LOCKED)
        val model = viewModel(service, TestScope(testScheduler))
        model.load()
        advanceUntilIdle()
        model.onPasswordChange("correct-horse")
        model.submit()
        advanceUntilIdle()

        model.lock()
        advanceUntilIdle()

        assertEquals(VaultUiState.Locked, model.screen)
        // lock() on the service is what discards the key and closes live clients.
        assertTrue("lock" in service.calls)
    }

    @Test
    fun `a configuration database that will not open becomes a visible message`() = runTest {
        val service = object : FakeConnectionService() {
            override suspend fun vaultState(): VaultState =
                throw StoreOpenException("The configuration database is unreadable.")
        }
        val model = viewModel(service, TestScope(testScheduler))

        model.load()
        advanceUntilIdle()

        val screen = assertIs<VaultUiState.Unavailable>(model.screen)
        assertEquals("store_unavailable", screen.failure.code)
    }
}
