package dev.caracal.engine.api

/**
 * Running a command the user typed.
 *
 * The one method is deliberately narrow, and what is missing from it is the point:
 * there is no way to send a command that skips the guard, because there is no other
 * way to send one. An engine whose read-only enforcement is
 * [ReadOnlyEnforcement.COMMAND_GUARD_ONLY] has no server-side transaction to fall
 * back on — a connection marked read only is read only because the implementation
 * behind this refuses to send anything else — so a caller that would rather not ask
 * does not get the choice.
 *
 * [consent] is what the user agreed to for *this* command and is never remembered
 * past it. That it is an argument rather than a setting is the enforcement of the
 * single-use rule: there is nowhere to store it, so there is nothing to leave
 * switched on.
 *
 * A command that needs an acknowledgement it has not been given fails rather than
 * running, and the failure carries the question to put on screen.
 */
interface CommandFacet {

    suspend fun execute(command: RawCommand, consent: CommandConsent = CommandConsent.None): CommandResult
}
