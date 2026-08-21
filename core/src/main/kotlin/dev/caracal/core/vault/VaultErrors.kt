package dev.caracal.core.vault

import kotlin.time.Duration

/**
 * A vault failure the UI can render.
 *
 * As with `DbError`, [code] is the stable case the UI branches on and [message] is
 * human-readable. Neither ever names a host, a user, or a password.
 */
sealed class VaultException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The message, non-null. `Throwable.message` is nullable; this one never is. */
    val safeMessage: String get() = message ?: code
}

/** An operation needed the master key while the application was locked. */
class VaultLockedException :
    VaultException("locked", "The application is locked.")

/** A second attempt to choose a master password. */
class AlreadySetUpException :
    VaultException("already_set_up", "A master password has already been set.")

/**
 * Vault metadata that is present but incomplete: a salt with no verifier.
 *
 * Distinct from [NotSetUpException] because the two look identical from the
 * outside and want opposite handling. Nothing has been set up means choose a
 * password; this means a password was chosen once, secrets may still be sealed
 * under it, and choosing a new one would make them unopenable.
 */
class VaultDamagedException :
    VaultException(
        "vault_damaged",
        "The vault's metadata is incomplete. Restore the configuration file from a backup " +
            "rather than setting a new master password, which would make saved credentials unreadable.",
    )

/** An unlock attempt before a master password was ever chosen. */
class NotSetUpException :
    VaultException("not_set_up", "No master password has been set.")

/**
 * The single answer to every failed unlock, so an attacker learns nothing beyond
 * "not this one".
 */
class WrongPasswordException :
    VaultException("wrong_password", "The master password is incorrect.")

/** The chosen master password is below the minimum length. */
class WeakPasswordException :
    VaultException(
        "weak_password",
        "The master password must be at least ${Vault.MIN_PASSWORD_LENGTH} characters.",
    )

/** Unlock attempts are in cooldown after repeated failures. */
class TooManyAttemptsException(val retryAfter: Duration) :
    VaultException(
        "too_many_attempts",
        "Too many failed attempts. Try again in ${retryAfter.inWholeSeconds} seconds.",
    )

/**
 * A sealed credential that will not open: a wrong key, tampering, or a record
 * copied from another connection.
 */
class SecretUnreadableException(cause: Throwable? = null) :
    VaultException("secret_unreadable", "This saved credential cannot be decrypted.", cause)

/** Bytes that are not a sealed envelope at all. */
class MalformedEnvelopeException(detail: String? = null) :
    VaultException(
        "malformed_envelope",
        "This saved credential is not readable." + (detail?.let { " ($it)" } ?: ""),
    )

/** Stored derivation parameters this build cannot use. */
class UnsupportedKdfException(detail: String) :
    VaultException("unsupported_kdf", "This configuration file cannot be opened: $detail.")
