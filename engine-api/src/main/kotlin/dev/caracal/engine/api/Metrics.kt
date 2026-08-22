package dev.caracal.engine.api

import kotlin.time.Duration

/*
 * A server's own summary of itself, moved here from `dev.caracal.core.redis`.
 *
 * `MetricsFacet` returns it and the dashboard draws it. The parser stays behind in
 * the engine: `INFO` is Redis's document, its allowlist is a disclosure decision
 * about Redis's fields, and `pg_stat_*` is not the same shape — which is §12's rule
 * about catalog queries, applied to the other thing a server will tell you about
 * itself.
 */

/** One database's entry in `INFO keyspace`. Databases with no keys are not listed. */
data class DatabaseKeyspace(val index: Int, val keys: Long, val expires: Long)

/**
 * The parts of `INFO` this build shows.
 *
 * Every field is nullable and that is the design, not laziness. `INFO` is a
 * different document on every Redis version, under every ACL, and in every
 * deployment mode: a user restricted from the `stats` section gets a reply with no
 * `keyspace_hits` in it, a Redis 6 server has no `listpack` counters, and none of
 * that is a failure — it is a dashboard with fewer cards. §3.8 asks for partial data
 * to stay usable, and a non-null field with a zero in it would be the one way to get
 * that wrong, because "nought hits" and "not allowed to know" would look identical.
 *
 * [restricted] is the stronger case: `INFO` itself was refused. Key browsing keeps
 * working, so this is a banner and not an error.
 */
data class ServerInfo(
    val version: String? = null,
    val mode: String? = null,
    val role: String? = null,
    val uptime: Duration? = null,
    val connectedClients: Long? = null,
    val connectedReplicas: Long? = null,
    val usedMemoryBytes: Long? = null,
    val maxMemoryBytes: Long? = null,
    val maxMemoryPolicy: String? = null,
    val keyspaceHits: Long? = null,
    val keyspaceMisses: Long? = null,
    val totalCommands: Long? = null,
    val opsPerSecond: Long? = null,
    val databases: List<DatabaseKeyspace> = emptyList(),
    val restricted: Boolean = false,
) {
    /**
     * Cache hits as a fraction of lookups, or `null` when there have been none.
     *
     * The guard §3.8 asks for. A server that has just started has nought hits and
     * nought misses, and the obvious division reports it as a 0% hit rate — which
     * reads as a catastrophically broken cache rather than as an idle one, on
     * precisely the screen someone opens when they suspect the cache is broken.
     */
    val hitRate: Double?
        get() {
            val hits = keyspaceHits ?: return null
            val misses = keyspaceMisses ?: return null
            val lookups = hits + misses
            return if (lookups <= 0) null else hits.toDouble() / lookups
        }

    /** Keys across every listed database, or `null` when the keyspace section was absent. */
    val totalKeys: Long?
        get() = if (restricted) null else databases.sumOf { it.keys }

    /** Whether anything at all came back. An empty summary is a card that should not draw. */
    val isEmpty: Boolean
        get() = version == null && usedMemoryBytes == null && connectedClients == null &&
            totalCommands == null && databases.isEmpty()
}

