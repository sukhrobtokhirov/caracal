package dev.caracal.engine.api

/** An engine's wire name: `postgres`, `redis`, `mysql`, `sqlite`. */
@JvmInline
value class EngineId(val value: String) {
    override fun toString(): String = value
}

/**
 * One database engine, as everything outside it sees it.
 *
 * An implementation is registered on the classpath and found by `ServiceLoader`, so
 * that adding an engine is adding a module and not editing a `when`. Phase 3 proves
 * that with a fake engine module that exists only in tests: if it appears in the
 * connection dialog with no UI change, the seam is real.
 */
interface DatabaseEngine {
    val id: EngineId

    /** Shown to people. `PostgreSQL`, not `postgres`. */
    val displayName: String

    val capabilities: EngineCapabilities

    /** The connection dialog, described rather than drawn. See [ConnectionForm]. */
    val connectionForm: ConnectionForm

    /** Null when the driver ships with the application, which is the usual case. */
    val driverRequirement: DriverRequirement?

    /**
     * Reads one statement or command and says what it looks like it will do.
     *
     * On the engine because classification is the half of §7 that is per-engine.
     * Core takes the answer, adds the environment and the writable flag, and decides
     * what the user has to agree to.
     */
    val intentClassifier: IntentClassifier

    /** Everything wrong with these settings. Empty means nothing was found wrong. */
    fun validate(descriptor: ConnectionDescriptor): List<ValidationIssue>

    /**
     * Dials, proves the connection works, and hands back a session.
     *
     * [policy] is the resolved safety decision, not the decision table: an engine
     * applies read-only enforcement at connect time, where the server can be told
     * about it, rather than leaving it to a classifier that a function body can walk
     * straight past.
     *
     * [drivers] is how a JDBC driver arrives without the engine knowing whether it
     * was bundled, downloaded, or imported from a memory stick. An engine whose
     * [driverRequirement] is null never calls it.
     */
    suspend fun connect(
        descriptor: ConnectionDescriptor,
        secrets: SecretBundle,
        policy: SessionPolicy,
        drivers: DriverProvider = DriverProvider.Bundled,
    ): DatabaseSession
}
