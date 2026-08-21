package dev.caracal.core.redis

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

/**
 * Parses Redis's `INFO` reply.
 *
 * Only the fields listed here are kept, which §3.8 asks for as version-independence
 * and which is also a disclosure decision. `INFO` reports `executable`,
 * `config_file`, `master_host`, and `run_id` among a few hundred other things — file
 * paths and addresses on the server, which the project's own rule says must not reach
 * a UI or a log. An allowlist keeps them out by construction rather than by
 * remembering to filter them at each place the summary is drawn.
 */
object RedisInfo {

    fun parse(text: String): ServerInfo {
        val fields = fields(text)
        return ServerInfo(
            version = fields["redis_version"],
            mode = fields["redis_mode"],
            role = fields["role"],
            uptime = fields.long("uptime_in_seconds")?.seconds,
            connectedClients = fields.long("connected_clients"),
            connectedReplicas = fields.long("connected_slaves"),
            usedMemoryBytes = fields.long("used_memory"),
            // Redis reports no limit as 0, which is not a maximum of nothing.
            maxMemoryBytes = fields.long("maxmemory")?.takeIf { it > 0 },
            maxMemoryPolicy = fields["maxmemory_policy"],
            keyspaceHits = fields.long("keyspace_hits"),
            keyspaceMisses = fields.long("keyspace_misses"),
            totalCommands = fields.long("total_commands_processed"),
            opsPerSecond = fields.long("instantaneous_ops_per_sec"),
            databases = databases(fields),
        )
    }

    /**
     * Every `key:value` line, ignoring section headers and blanks.
     *
     * Split on the *first* colon only. Several values contain colons of their own —
     * `db0:keys=1,expires=0` is parsed further below, and an address-shaped value
     * would otherwise lose everything after its first one.
     */
    private fun fields(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            line.substring(0, separator) to line.substring(separator + 1)
        }
        // Last wins, which is what a duplicated field means in a document read top to
        // bottom. Redis does not emit one, but a proxy in front of it might.
        .toMap()

    /**
     * The `keyspace` section: `db0:keys=1,expires=0,avg_ttl=0`.
     *
     * Sorted by index, because Redis emits them in whatever order it walks and a
     * dashboard whose rows move between refreshes is a dashboard nobody can compare
     * against itself.
     */
    private fun databases(fields: Map<String, String>): List<DatabaseKeyspace> = fields
        .mapNotNull { (name, value) ->
            val index = name.removePrefix("db").takeIf { name.startsWith("db") }?.toIntOrNull()
                ?: return@mapNotNull null
            val parts = value.split(',')
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) null else part.substring(0, separator).trim() to part.substring(separator + 1)
                }
                .toMap()
            val keys = parts["keys"]?.toLongOrNull() ?: return@mapNotNull null
            DatabaseKeyspace(index = index, keys = keys, expires = parts["expires"]?.toLongOrNull() ?: 0)
        }
        .sortedBy { it.index }

    private fun Map<String, String>.long(name: String): Long? = this[name]?.trim()?.toLongOrNull()
}
