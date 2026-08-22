package dev.caracal.engine.api

import java.time.Instant

/**
 * A resolved secret, in whichever shape its engine authenticates with.
 *
 * A `CharArray` rather than a `String` throughout, for the reason the vault already
 * observes: a `String` is immutable, so the only way to stop holding a password is
 * to wait for a garbage collector that has no obligation to hurry. The caller owns
 * the arrays and should clear them once the driver has taken what it needs.
 *
 * The set is wider than anything stored today on purpose. Adding an arm later means
 * migrating every record already written, and the versioned envelope that makes such
 * a migration survivable is Phase 4's work — this is the shape it will migrate to.
 */
sealed interface SecretBundle {
    /** SQLite, and an unauthenticated Redis. Not "we could not find one". */
    data object None : SecretBundle

    data class Password(val password: CharArray) : SecretBundle

    data class UserPassword(val user: String, val password: CharArray) : SecretBundle

    data class ClientCertificate(val keyStore: ByteArray, val passphrase: CharArray) : SecretBundle

    /** The escape hatch, and the one most likely to end up in a log. Redact it. */
    data class ConnectionString(val value: CharArray) : SecretBundle

    /** A credential that expires — RDS IAM and its relatives. */
    data class Token(val value: CharArray, val expiresAt: Instant? = null) : SecretBundle
}

/** What a secret field on a connection form is collecting. */
enum class SecretKind { PASSWORD, PASSPHRASE, TOKEN, CONNECTION_STRING }
