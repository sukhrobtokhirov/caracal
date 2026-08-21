package dev.caracal.core.redis

import dev.caracal.core.connections.ConnectionConfig
import dev.caracal.core.connections.Environment
import dev.caracal.core.policy.Acknowledgement
import dev.caracal.core.result.DbError

/** What has to happen before a console command is sent. */
sealed interface CommandClearance {
    /** Send it. */
    data object Granted : CommandClearance

    /**
     * Ask first, in these terms.
     *
     * [warning] says what the command does, because "are you sure?" above a command
     * the user just typed adds nothing. Knowing that `SWAPDB` exchanges two whole
     * databases might.
     */
    data class Confirm(
        val command: String,
        val acknowledgement: Acknowledgement,
        val connectionName: String,
        val environment: Environment,
        val warning: String,
    ) : CommandClearance {
        /**
         * The exact text the user must type, or `null` when a click is enough.
         *
         * §3.10 asks for the connection name *plus the command name*, and the second
         * half is what makes it more than a formality: a phrase that is only the
         * connection name is one a user learns and retypes by rote, and the command is
         * the part that differs between the flush they meant and the flush they did
         * not.
         */
        val phrase: String? = "$connectionName $command".takeIf { acknowledgement == Acknowledgement.TYPED }

        /** Whether [typed] satisfies this confirmation. Trimmed, but not case-folded. */
        fun satisfiedBy(typed: String): Boolean {
            val phrase = phrase ?: return true
            return typed.trim() == phrase
        }
    }

    /** Do not send it. [error] is what to say, and carries whether an override could ever help. */
    data class Refused(val error: DbError.CommandNotAllowed) : CommandClearance
}

/**
 * Decides whether a raw console command may be sent, and what the user must agree to
 * first.
 *
 * Unlike [dev.caracal.core.policy.DataSafetyPolicy], which is the *warning* half of a
 * rule the PostgreSQL server enforces underneath it, this is the enforcement. Redis
 * has no read-only transaction to open: a connection marked read only is read only
 * because [RedisAdapter] refuses to send anything else. So this is consulted on every
 * console command, from `:core`, and a caller cannot get past it by not asking —
 * the adapter asks.
 *
 * Which does not make it a security boundary, and §3.10 says so plainly: a Redis ACL
 * is the control that holds against something that is not this application. This is
 * the control that holds against a person with the right credentials, the wrong
 * window focused, and muscle memory.
 *
 * The table, completely:
 *
 * | Command | Read-only connection | dev / staging | prod |
 * |---|---|---|---|
 * | known read | granted | granted | granted |
 * | dangerous | refused | click | typed phrase |
 * | anything else | refused | granted | typed phrase |
 *
 * The bottom-right cell is more than §3.10 asks for and is deliberate. It is the row
 * `DataSafetyPolicy` already applies to SQL — on production, a statement that is not
 * known to be a read is confirmed — and the alternative is an application that makes
 * you type a phrase to run `FLUSHDB` against production and lets `DEL` through on a
 * click. The classification costs nothing extra: it is the same allowlist the
 * read-only column is already built from, read for the same question.
 */
object RedisCommandGuard {

    fun clearanceFor(command: RedisCommand, connection: ConnectionConfig): CommandClearance {
        val dangerous = dangerReason(command)

        if (connection.readOnly) {
            // Checked before the allowlist so the message names the real objection.
            // `KEYS` is on nobody's allowlist, but "this connection is read only" is
            // not why it is refused, and saying so would send a user looking for a
            // writable connection to run it on.
            if (dangerous != null) {
                return refuse(
                    command,
                    "$dangerous It is blocked on a read-only connection, and no override applies.",
                )
            }
            if (!isKnownRead(command)) {
                return refuse(
                    command,
                    "This connection is read only, and ${command.label} is not one of the commands " +
                        "known to only read.",
                )
            }
            return CommandClearance.Granted
        }

        val production = connection.environment == Environment.PROD
        return when {
            dangerous != null -> confirm(
                command = command,
                connection = connection,
                // Typed on production; typed nowhere else. A dangerous command on a
                // development database is a thing people do on purpose all day.
                acknowledgement = if (production) Acknowledgement.TYPED else Acknowledgement.CLICK,
                warning = dangerous,
            )

            isKnownRead(command) -> CommandClearance.Granted

            production -> confirm(
                command = command,
                connection = connection,
                acknowledgement = Acknowledgement.TYPED,
                warning = "${command.label} is not one of the commands known to only read, and this " +
                    "connection is production.",
            )

            else -> CommandClearance.Granted
        }
    }

    private fun refuse(command: RedisCommand, message: String) = CommandClearance.Refused(
        DbError.CommandNotAllowed(
            message = message,
            command = command.label,
            reason = DbError.CommandNotAllowed.Reason.READ_ONLY,
        ),
    )

    private fun confirm(
        command: RedisCommand,
        connection: ConnectionConfig,
        acknowledgement: Acknowledgement,
        warning: String,
    ) = CommandClearance.Confirm(
        command = command.label,
        acknowledgement = acknowledgement,
        connectionName = connection.name,
        environment = connection.environment,
        warning = warning,
    )

    /** Why this command is dangerous, or `null` when it is not one of them. */
    fun dangerReason(command: RedisCommand): String? =
        DANGEROUS[command.label] ?: DANGEROUS[command.name]

    /**
     * Whether this command is on the list of ones known to only read.
     *
     * An allowlist and not a denylist, which §3.10 is explicit about: Redis has
     * several hundred commands, gains more with every release, and gains arbitrarily
     * many more with a loaded module. A denylist of writes is a list that is wrong
     * the first time somebody installs RedisJSON, and wrong in the direction that
     * lets a write through.
     */
    fun isKnownRead(command: RedisCommand): Boolean = when {
        dangerReason(command) != null -> false
        command.name in READ_CONTAINERS -> true
        command.label in READ_SUBCOMMANDS -> true
        else -> command.name in READS
    }

    /**
     * The commands that are blocked, and what each one does.
     *
     * The eight §3.10 names are the floor, not the list. Its closing instruction is to
     * extend it whenever a command "can block the server, expose sensitive
     * configuration, or destroy broad data", and each addition below is one of those
     * three:
     *
     * - **Blocks the server.** `SAVE` writes the whole dataset synchronously on the
     *   one thread that serves every client, which is the same failure `KEYS` causes
     *   and is easier to trigger by accident. The scripting commands run code of
     *   unbounded duration on that same thread.
     * - **Destroys broad data.** `REPLICAOF`/`SLAVEOF` do not look destructive and
     *   discard the entire dataset in favour of another server's. `MIGRATE` moves keys
     *   off this server. `FAILOVER` and `CLUSTER RESET` rearrange who holds what.
     * - **Exposes or changes credentials.** All of `ACL` — `GETUSER` returns password
     *   hashes and `SETUSER` grants access — which is why the whole command is here
     *   rather than a list of its subcommands.
     *
     * `CONFIG GET` is deliberately *not* here even though it can read `requirepass`,
     * because the reply goes to someone already holding the password that opened the
     * connection, and reading `maxmemory` and `maxmemory-policy` is most of what
     * diagnosing a cache problem consists of.
     */
    private val DANGEROUS: Map<String, String> = mapOf(
        // §3.10's required eight.
        "FLUSHALL" to "FLUSHALL deletes every key in every database on this server.",
        "FLUSHDB" to "FLUSHDB deletes every key in the current database.",
        "KEYS" to "KEYS walks the entire keyspace in one command and blocks the server " +
            "until it finishes. The key browser does the same job with bounded SCAN.",
        "SHUTDOWN" to "SHUTDOWN stops the server.",
        "DEBUG" to "DEBUG is a maintenance command that can corrupt data or hang the server.",
        "CONFIG SET" to "CONFIG SET changes the server's running configuration.",
        "MONITOR" to "MONITOR streams every command from every client and slows the whole server.",
        "SWAPDB" to "SWAPDB exchanges the contents of two databases.",

        // Blocks the server.
        "SAVE" to "SAVE writes the whole dataset to disk on the thread that serves every " +
            "client, blocking all of them until it finishes.",
        "EVAL" to "EVAL runs a script that can read, write, and run for as long as it likes.",
        "EVALSHA" to "EVALSHA runs a stored script that can read, write, and run for as long as it likes.",
        "EVAL_RO" to "EVAL_RO runs a script, which cannot write but can still run for as long as it likes.",
        "EVALSHA_RO" to "EVALSHA_RO runs a stored script, which cannot write but can still run " +
            "for as long as it likes.",
        "FCALL" to "FCALL runs a stored function that can read, write, and run for as long as it likes.",
        "FCALL_RO" to "FCALL_RO runs a stored function, which cannot write but can still run " +
            "for as long as it likes.",
        "CLIENT PAUSE" to "CLIENT PAUSE stops the server answering other clients.",
        "CLIENT UNPAUSE" to "CLIENT UNPAUSE changes how the server is answering other clients.",
        "CLIENT KILL" to "CLIENT KILL disconnects other clients.",
        "CLIENT NO-EVICT" to "CLIENT NO-EVICT changes how the server sheds load under memory pressure.",

        // Destroys broad data.
        "REPLICAOF" to "REPLICAOF discards this server's entire dataset and replaces it with another's.",
        "SLAVEOF" to "SLAVEOF discards this server's entire dataset and replaces it with another's.",
        "MIGRATE" to "MIGRATE moves keys off this server onto another one.",
        "FAILOVER" to "FAILOVER hands this server's role to a replica.",
        "RESET" to "RESET discards this connection's state, including which database it is on.",
        "CLUSTER RESET" to "CLUSTER RESET removes this node from its cluster and can discard its data.",
        "CLUSTER FAILOVER" to "CLUSTER FAILOVER hands this node's slots to a replica.",
        "SCRIPT FLUSH" to "SCRIPT FLUSH discards every script the server has cached.",
        "FUNCTION" to "FUNCTION loads, replaces, or deletes the server's stored functions.",

        // Exposes or changes credentials.
        "ACL" to "ACL reads and changes who may connect to this server and what they may do.",

        // Loses history other people may be relying on.
        "CONFIG RESETSTAT" to "CONFIG RESETSTAT discards the server's accumulated statistics.",
        "CONFIG REWRITE" to "CONFIG REWRITE overwrites the server's configuration file.",
        "SLOWLOG RESET" to "SLOWLOG RESET discards the slow-query log.",
        "LATENCY RESET" to "LATENCY RESET discards the recorded latency history.",
    )

    /**
     * Container commands whose every subcommand only reads.
     *
     * Listed as containers rather than as pairs so that a subcommand added by a later
     * Redis release is covered. That is safe for exactly these four: they are the
     * introspection commands, and a new `XINFO` subcommand will report on a stream.
     * `CLIENT`, `CONFIG`, `MEMORY`, and `SLOWLOG` are *not* here for the opposite
     * reason — each already mixes reads with something that is not one.
     */
    private val READ_CONTAINERS = setOf("COMMAND", "OBJECT", "PUBSUB", "XINFO")

    /** Read subcommands of containers that also offer something else. */
    private val READ_SUBCOMMANDS = setOf(
        "CONFIG GET",
        "MEMORY USAGE", "MEMORY STATS", "MEMORY DOCTOR", "MEMORY MALLOC-STATS",
        "CLIENT ID", "CLIENT INFO", "CLIENT GETNAME", "CLIENT LIST",
        "SLOWLOG GET", "SLOWLOG LEN", "SLOWLOG HELP",
        "LATENCY HISTORY", "LATENCY LATEST", "LATENCY DOCTOR",
        "XGROUP HELP",
    )

    /**
     * Commands known to only read.
     *
     * Two omissions worth naming, because both look like reads:
     *
     * - `SORT` takes a `STORE` option and writes when it is given one. `SORT_RO` is
     *   the variant that cannot, and is the one here.
     * - `PFCOUNT` is documented as a write: it updates the cached cardinality inside
     *   the HyperLogLog it was asked to count.
     *
     * `GETDEL` and `GETEX` are absent for the reason their names give.
     */
    private val READS = setOf(
        // Connection and server introspection.
        "PING", "ECHO", "TIME", "DBSIZE", "LASTSAVE", "INFO", "LOLWUT", "WAIT",
        // Keyspace.
        "TYPE", "TTL", "PTTL", "EXPIRETIME", "PEXPIRETIME", "EXISTS", "SCAN",
        "RANDOMKEY", "DUMP", "TOUCH", "SORT_RO",
        // Strings.
        "GET", "MGET", "GETRANGE", "SUBSTR", "STRLEN", "GETBIT", "BITCOUNT", "BITPOS",
        // Hashes.
        "HGET", "HMGET", "HGETALL", "HKEYS", "HVALS", "HLEN", "HSTRLEN", "HEXISTS",
        "HSCAN", "HRANDFIELD",
        // Sets.
        "SMEMBERS", "SCARD", "SISMEMBER", "SMISMEMBER", "SRANDMEMBER", "SSCAN",
        "SDIFF", "SINTER", "SUNION", "SINTERCARD",
        // Lists.
        "LLEN", "LRANGE", "LINDEX", "LPOS",
        // Sorted sets.
        "ZCARD", "ZSCORE", "ZMSCORE", "ZCOUNT", "ZLEXCOUNT", "ZRANGE", "ZREVRANGE",
        "ZRANGEBYSCORE", "ZREVRANGEBYSCORE", "ZRANGEBYLEX", "ZREVRANGEBYLEX",
        "ZRANK", "ZREVRANK", "ZRANDMEMBER", "ZSCAN", "ZDIFF", "ZINTER", "ZUNION",
        "ZINTERCARD",
        // Streams.
        "XLEN", "XRANGE", "XREVRANGE", "XPENDING",
        // Geo, which is a sorted set underneath.
        "GEOPOS", "GEODIST", "GEOHASH", "GEOSEARCH",
        // Bitfields, read-only variant only.
        "BITFIELD_RO",
    )
}
