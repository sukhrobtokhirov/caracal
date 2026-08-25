package dev.caracal.core.redis

import dev.caracal.engine.api.DatabaseKeyspace
import dev.caracal.engine.api.ServerInfo
import kotlin.time.Duration.Companion.seconds

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
