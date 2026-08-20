/**
 * What a statement is allowed to do, and what the user has to agree to first.
 *
 * This package is the *warning* half of §2.4. The enforcing half is a PostgreSQL
 * `READ ONLY` transaction on a read-only connection's pool, which lives in
 * `postgres/` and decides on the server, where a write hidden in a function body
 * compiled last year is still a write. Nothing here is a security boundary and
 * nothing here should ever be treated as one.
 *
 * What it is for is the thing the server cannot do: tell the user what is about to
 * happen while there is still time to stop it.
 */
package dev.dbide.core.policy

import dev.dbide.core.connections.ConnectionConfig
import dev.dbide.core.connections.Environment
import dev.dbide.core.sql.StatementClassifier
import dev.dbide.core.sql.StatementKind

/** How firmly the user has to agree before a statement is sent. */
enum class Acknowledgement {
    /** A button. Enough for a development or staging database. */
    CLICK,

    /**
     * The connection's name, typed out. Production only.
     *
     * The point of typing is not that it is hard, it is that it cannot be done by
     * reflex. A confirmation you dismiss without reading is not a confirmation, and
     * the muscle memory for "click the right-hand button" is exactly what a
     * production mistake is made of.
     */
    TYPED,
}

/** Why a statement will not be sent at all. */
enum class WriteRefusal(val code: String, val message: String) {
    READ_ONLY_CONNECTION(
        "read_only_connection",
        "This connection is marked read only, so it will not run a statement that modifies data.",
    ),

    /**
     * §2.4's uncertainty rule. On a writable connection an unrecognized statement is
     * confirmed; on a read-only one it is refused, because "we could not tell" and
     * "this connection must not write" together can only be resolved one way.
     */
    UNCLASSIFIED_ON_READ_ONLY(
        "unclassified_statement",
        "This connection is marked read only, and this statement could not be recognized " +
            "as one that only reads.",
    ),
}

/** What the policy requires before [DataSafetyPolicy] will let a statement go. */
sealed interface Clearance {
    /** Send it. */
    data object Granted : Clearance

    /** Ask first, in the terms this carries. */
    data class Confirm(
        val kind: StatementKind,
        val acknowledgement: Acknowledgement,
        val connectionName: String,
        val environment: Environment,
    ) : Clearance {
        /** The exact text the user must type, or `null` when a click is enough. */
        val phrase: String? get() = connectionName.takeIf { acknowledgement == Acknowledgement.TYPED }

        /** Whether [typed] satisfies this confirmation. Trimmed, but not case-folded. */
        fun satisfiedBy(typed: String): Boolean {
            val phrase = phrase ?: return true
            return typed.trim() == phrase
        }
    }

    /** Do not send it, and say this. */
    data class Refused(val code: String, val message: String) : Clearance {
        constructor(refusal: WriteRefusal) : this(refusal.code, refusal.message)
    }
}

/**
 * Decides what has to happen before a statement reaches a server.
 *
 * Three inputs, and they are all the policy has: what the statement looks like it
 * does, whether the connection is marked read only, and which environment it is.
 * The table is small enough to state completely:
 *
 * | Statement | Read-only connection | dev / staging | prod |
 * |---|---|---|---|
 * | reads | granted | granted | granted |
 * | session/transaction control | granted | granted | granted |
 * | writes and DDL | refused | click | typed name |
 * | unrecognized | refused | click | typed name |
 *
 * Session statements are granted everywhere on purpose. Each one runs in its own
 * transaction, so a `SET` on a read-only connection is rolled back before the next
 * statement starts, and refusing it would be refusing something that has already
 * been made harmless. `StatementClassifier` keeps them in their own case so the
 * editor can say that rather than calling them writes.
 */
object DataSafetyPolicy {

    fun clearanceFor(sql: String, connection: ConnectionConfig): Clearance =
        when (val kind = StatementClassifier.classify(sql)) {
            StatementKind.READ, StatementKind.SESSION -> Clearance.Granted

            StatementKind.WRITE ->
                if (connection.readOnly) Clearance.Refused(WriteRefusal.READ_ONLY_CONNECTION)
                else confirm(kind, connection)

            StatementKind.UNKNOWN ->
                if (connection.readOnly) Clearance.Refused(WriteRefusal.UNCLASSIFIED_ON_READ_ONLY)
                else confirm(kind, connection)
        }

    private fun confirm(kind: StatementKind, connection: ConnectionConfig) = Clearance.Confirm(
        kind = kind,
        acknowledgement = when (connection.environment) {
            Environment.PROD -> Acknowledgement.TYPED
            Environment.STAGING, Environment.DEV -> Acknowledgement.CLICK
        },
        connectionName = connection.name,
        environment = connection.environment,
    )
}
