package dev.caracal.engine.redis

import dev.caracal.core.redis.CommandLine
import dev.caracal.core.redis.RedisCommandGuard
import dev.caracal.engine.api.IntentClassifier
import dev.caracal.engine.api.WriteIntent

/**
 * Redis's half of §7, and a different shape from PostgreSQL's for a reason §12
 * insists on: the command guard is an *allowlist* of commands known to only read,
 * not a denylist of writes. Redis has several hundred commands, gains more with
 * every release, and gains arbitrarily many more with a loaded module — a denylist
 * is wrong the first time somebody installs RedisJSON, and wrong in the direction
 * that lets a write through.
 *
 * So the three answers here are the three the guard actually has, and no more.
 *
 * A command on the dangerous list becomes [WriteIntent.DESTRUCTIVE]. The guard keeps
 * one list for "blocks the server, exposes sensitive configuration, or destroys
 * broad data" and does not subdivide it: `KEYS` is on it for stalling every other
 * client rather than for destroying anything, and `ACL GETUSER` for returning
 * password hashes. Splitting them into [WriteIntent.DESTRUCTIVE] and
 * [WriteIntent.SERVER_AFFECTING] here would mean a second table of Redis command
 * names maintained beside the guard's — which is exactly what §12 says must not
 * happen, for no gain, since what core does with both answers is the same.
 *
 * Anything that is neither dangerous nor a known read becomes [WriteIntent.UNKNOWN]
 * and never [WriteIntent.WRITE]. That is not caution for its own sake: with an
 * allowlist, "not on it" genuinely means we do not know, and saying `UNKNOWN` is the
 * difference between core refusing it on a read-only connection and core waving
 * through something it has merely mislabelled.
 */
object RedisIntent : IntentClassifier {

    override fun classify(statement: String): WriteIntent {
        // An unparseable line is not a read. `CommandLine.split` handles quoting and
        // escapes and can still be handed something with no command in it at all.
        val command = runCatching { CommandLine.command(statement) }.getOrNull()
            ?: return WriteIntent.UNKNOWN
        if (command.size == 0) return WriteIntent.UNKNOWN

        return when {
            RedisCommandGuard.dangerReason(command) != null -> WriteIntent.DESTRUCTIVE
            RedisCommandGuard.isKnownRead(command) -> WriteIntent.READ_ONLY
            else -> WriteIntent.UNKNOWN
        }
    }
}
