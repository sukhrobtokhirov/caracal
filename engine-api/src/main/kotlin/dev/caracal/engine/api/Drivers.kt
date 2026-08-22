package dev.caracal.engine.api

/**
 * The driver an engine needs, when it is not one we are allowed to ship.
 *
 * Null on [DatabaseEngine.driverRequirement] means bundled, which is the case
 * Caracal defaults to and the case worth defending: a default install connects to
 * PostgreSQL, Redis and SQLite with no network access of any kind. A non-null
 * requirement means the licence forbids bundling — MySQL's GPL, MariaDB's LGPL,
 * Oracle's click-through — and that adding such a connection will, once, ask the
 * user to approve a download.
 *
 * Runtime provisioning applies **only to JDBC drivers**, because `java.sql.Driver`
 * is a stable API that can be reached reflectively across a classloader boundary.
 * A non-JDBC client — Lettuce, some future Mongo driver — has no such surface and
 * must be a compile-time dependency. So a requirement is only expressible for an
 * engine whose family is [EngineFamily.SQL]; `EngineCapabilitiesTest` holds the
 * registered engines to it.
 *
 * [specId] names an entry in the manifest that ships inside the application jar.
 * The manifest is not fetched over the network, and Phase 5 writes down why at
 * length: a manifest an attacker can rewrite is a manifest that chooses which code
 * you load, and the pinned checksums beneath it become decoration.
 */
data class DriverRequirement(
    val specId: String,
    val displayName: String,
)

/**
 * Hands an engine a driver that is already on disk and already verified.
 *
 * Never downloads. An engine asking for a driver is in the middle of connecting, and
 * a connection attempt is not the place to discover the network is down and start a
 * consent dialog. Provisioning is a separate, deliberate act; this is the lookup.
 */
fun interface DriverProvider {
    /** The bundle for this requirement, or null when it is not installed. */
    fun resolve(requirement: DriverRequirement): DriverBundle?

    companion object {
        /**
         * The provider for engines whose drivers ship with the application.
         *
         * It answers null to everything, which is correct rather than lazy: an engine
         * with a null [DatabaseEngine.driverRequirement] never asks.
         */
        val Bundled: DriverProvider = DriverProvider { null }
    }
}

/**
 * One classloaded JDBC driver, and the loader that owns it.
 *
 * `newDriver` rather than a registered one, because `DriverManager` cannot see a
 * class from a foreign loader and would not be told about it anyway. Calling
 * `driver.connect(url, props)` directly also means the driver never becomes globally
 * visible, which is the property worth having.
 */
interface DriverBundle : AutoCloseable {
    val requirement: DriverRequirement

    fun newDriver(): java.sql.Driver
}
