package dev.caracal.core.policy

import dev.caracal.core.result.DbError
import dev.caracal.core.result.DbException
import dev.caracal.engine.api.Environment

/*
 * What the user must agree to before a console command is sent, and the question
 * that gets asked when they have not.
 *
 * Both used to live in the Redis engine, next to the guard that produces them. They
 * are here because §7's split puts classifying in the engine and deciding in core:
 * the guard still decides which commands are dangerous, because only Redis knows
 * what `SWAPDB` does, but a clearance is the *decision*, and the console that draws
 * the question must be able to read one without being able to see Redis.
 *
 * The guard itself stays where it is. §12 is explicit that it must not become a
 * generic dangerous-statement classifier, and moving its output is not moving it.
 */

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
 * Reported when a console command needs an acknowledgement that was not given.
 *
 * Carries the [clearance] so the UI can put the right question on screen — which
 * button, which phrase to type, and what the command actually does. Everything else
 * sees an ordinary [DbException] saying the command was not allowed, which is true.
 */
class CommandConfirmationRequired(val clearance: CommandClearance.Confirm) : DbException(
    DbError.CommandNotAllowed(message = clearance.warning, command = clearance.command),
)
