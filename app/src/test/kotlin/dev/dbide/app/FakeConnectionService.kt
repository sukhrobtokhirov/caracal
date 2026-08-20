package dev.dbide.app

import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.ConnectionDraft
import dev.dbide.core.connections.ConnectionId
import dev.dbide.core.connections.ConnectionService
import dev.dbide.core.connections.ConnectionSummary
import dev.dbide.core.connections.ConnectionView
import dev.dbide.core.connections.Engine
import dev.dbide.core.connections.Environment
import dev.dbide.core.connections.RuntimeState
import dev.dbide.core.connections.RuntimeStatus
import dev.dbide.core.connections.Secret
import dev.dbide.core.connections.SecretUpdate
import dev.dbide.core.connections.TestResult
import dev.dbide.core.connections.TlsMode
import dev.dbide.core.connections.ValidationException
import dev.dbide.core.store.ConnectionNotFoundException
import dev.dbide.core.vault.VaultLockedException
import dev.dbide.core.vault.VaultState
import dev.dbide.core.vault.WrongPasswordException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred

/**
 * A stand-in for the real service.
 *
 * The rules `:core` enforces are tested against `:core`. What the UI needs from a
 * double is the shape of the answers and the ability to pause one mid-flight, which
 * is how a "busy" state gets asserted at all.
 */
open class FakeConnectionService(
    private var state: VaultState = VaultState.SETUP_REQUIRED,
    private val masterPassword: String = "correct-horse",
) : ConnectionService {

    val stored = linkedMapOf<ConnectionId, ConnectionView>()

    /** Names of the calls made, in order. Tests assert on what was, and was not, called. */
    val calls = mutableListOf<String>()

    /** The draft each save was given, so a test can prove what the form sent. */
    val drafts = mutableListOf<ConnectionDraft>()

    /** Set to hold the next operation open until the test completes it. */
    var gate: CompletableDeferred<Unit>? = null

    /** Set to make the next operation fail. */
    var nextFailure: Throwable? = null

    var testResult = TestResult(Engine.POSTGRES, "16.2", latencyMillis = 12)

    private var counter = 0

    override suspend fun vaultState(): VaultState = state

    override suspend fun setUp(password: Secret) {
        calls += "setUp"
        await()
        state = VaultState.UNLOCKED
    }

    override suspend fun unlock(password: Secret) {
        calls += "unlock"
        await()
        if (password.expose() != masterPassword) throw WrongPasswordException()
        state = VaultState.UNLOCKED
    }

    override suspend fun lock() {
        calls += "lock"
        state = VaultState.LOCKED
    }

    override suspend fun list(): List<ConnectionView> {
        calls += "list"
        await()
        requireUnlocked()
        return stored.values.sortedWith(
            compareBy({ it.config.environment.severity }, { it.config.name.lowercase() }),
        )
    }

    override suspend fun get(id: ConnectionId): ConnectionView {
        requireUnlocked()
        return stored[id] ?: throw ConnectionNotFoundException(id)
    }

    override suspend fun create(draft: ConnectionDraft): ConnectionView {
        calls += "create"
        drafts += draft
        await()
        requireUnlocked()
        val normalized = draft.normalized()
        normalized.validate().takeIf { it.isNotEmpty() }?.let { throw ValidationException(it) }
        val id = ConnectionId("id-${++counter}")
        return view(normalized.toConfig(id, CREATED_AT), draft.secret.storesSecret())
            .also { stored[id] = it }
    }

    override suspend fun update(id: ConnectionId, draft: ConnectionDraft): ConnectionView {
        calls += "update"
        drafts += draft
        await()
        requireUnlocked()
        val existing = stored[id] ?: throw ConnectionNotFoundException(id)
        val normalized = draft.normalized()
        normalized.validate().takeIf { it.isNotEmpty() }?.let { throw ValidationException(it) }
        val hasSecret = when (draft.secret) {
            SecretUpdate.Unchanged -> existing.hasSecret
            else -> draft.secret.storesSecret()
        }
        return view(normalized.toConfig(id, existing.config.createdAt), hasSecret, existing.runtime)
            .also { stored[id] = it }
    }

    override suspend fun delete(id: ConnectionId) {
        calls += "delete"
        await()
        requireUnlocked()
        stored.remove(id) ?: throw ConnectionNotFoundException(id)
    }

    override suspend fun test(id: ConnectionId): TestResult {
        calls += "test"
        await()
        requireUnlocked()
        return testResult
    }

    override suspend fun open(id: ConnectionId): ConnectionView {
        calls += "open"
        await()
        requireUnlocked()
        val existing = stored.getValue(id)
        return existing.copy(runtime = RuntimeState(RuntimeStatus.OPEN, openedAt = CREATED_AT))
            .also { stored[id] = it }
    }

    override suspend fun close(id: ConnectionId): ConnectionView {
        calls += "close"
        await()
        requireUnlocked()
        val existing = stored.getValue(id)
        return existing.copy(runtime = RuntimeState.CLOSED).also { stored[id] = it }
    }

    override suspend fun shutdown() {
        calls += "shutdown"
    }

    /** Seeds a saved connection without going through the form. */
    fun seed(
        name: String = "Local",
        engine: Engine = Engine.POSTGRES,
        environment: Environment = Environment.DEV,
        readOnly: Boolean = false,
        hasSecret: Boolean = true,
        status: RuntimeStatus = RuntimeStatus.CLOSED,
    ): ConnectionView {
        val id = ConnectionId("id-${++counter}")
        val config = ConnectionConfig(
            id = id,
            name = name,
            engine = engine,
            host = "localhost",
            port = engine.defaultPort,
            database = if (engine == Engine.REDIS) "0" else "dbide",
            username = "dbide",
            tlsMode = TlsMode.DISABLE,
            environment = environment,
            readOnly = readOnly,
            color = null,
            createdAt = CREATED_AT,
        )
        return view(config, hasSecret, RuntimeState(status)).also { stored[id] = it }
    }

    private fun requireUnlocked() {
        if (state != VaultState.UNLOCKED) throw VaultLockedException()
    }

    private suspend fun await() {
        gate?.await()
        nextFailure?.let {
            nextFailure = null
            throw it
        }
    }

    private fun view(
        config: ConnectionConfig,
        hasSecret: Boolean,
        runtime: RuntimeState = RuntimeState.CLOSED,
    ) = ConnectionView(ConnectionSummary(config, hasSecret), runtime)

    private fun SecretUpdate.storesSecret(): Boolean = this is SecretUpdate.Replace && !secret.isEmpty()

    companion object {
        val CREATED_AT: Instant = Instant.parse("2026-08-20T10:00:00Z")
    }
}
