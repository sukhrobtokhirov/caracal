package dev.caracal.core.vault

import dev.caracal.core.connections.charsToUtf8
import dev.caracal.core.connections.utf8ToChars
import dev.caracal.engine.api.SecretBundle
import java.time.Instant

/** The record schema a v0.1.0 installation wrote: the password's bytes, and nothing else. */
const val RECORD_VERSION_LEGACY = 1

/** The record schema this build writes. */
const val RECORD_VERSION = 2

/**
 * The versioned plaintext inside a sealed envelope.
 *
 * A stored secret used to be a password, so the plaintext could be the password's
 * bytes. It is now a [SecretBundle], because a client certificate and a connection
 * string are not passwords and an engine that wants one has no way to say so through
 * a `CharArray`. That change is only survivable if the record says which shape it is
 * in, which is what [schemaVersion] is for: a v1 record is still readable, forever,
 * by a build that has never written one.
 *
 * ### Why this is bytes rather than JSON
 *
 * §5.3 of the spec describes this record as a `JsonObject` payload. It is not one,
 * and the reason is the reason `Secret` exists: kotlinx.serialization's JSON reads
 * and writes through `String`, so every password would spend the decode as an
 * immutable object that cannot be wiped and that only a garbage collector under no
 * obligation to hurry will ever release. The whole vault is built to avoid exactly
 * that, and a format choice that undoes it inside the decrypt path is the one place
 * it can least afford to be undone. Every field below is length-prefixed bytes,
 * copied into the `CharArray` it belongs in and wiped on the way past.
 *
 * ### Why the version is a byte and not a field
 *
 * The spec's record carries `schemaVersion` inside the serialized object. Two
 * version numbers — the envelope's leading byte and a field inside what that byte
 * says how to parse — can disagree, and the one that has to be trusted is the one
 * read before parsing begins. So there is a single number, it is the first byte of
 * the plaintext, and it is the same byte the v1 format already had.
 */
data class VaultRecord(val schemaVersion: Int, val secret: SecretBundle) {
    /** Whether this build would write it differently than it was read. */
    val isCurrent: Boolean get() = schemaVersion == RECORD_VERSION
}

/**
 * Clears every sensitive array a bundle holds.
 *
 * The SPI says the caller owns the arrays and should clear them once the driver has
 * taken what it needs; this is `:core` saying it, once, rather than each of the four
 * places that hold a decrypted bundle for the length of one operation. A `String`
 * field — the user name — is deliberately not among them: there is nothing to clear
 * and pretending otherwise would suggest there was.
 */
internal fun SecretBundle.wipe() {
    when (this) {
        is SecretBundle.None -> Unit
        is SecretBundle.Password -> password.fill(' ')
        is SecretBundle.UserPassword -> password.fill(' ')
        is SecretBundle.ClientCertificate -> {
            keyStore.wipe()
            passphrase.fill(' ')
        }

        is SecretBundle.ConnectionString -> value.fill(' ')
        is SecretBundle.Token -> value.fill(' ')
    }
}

/** The discriminator byte. Values are permanent: a stored record names one. */
private object Kind {
    const val NONE: Byte = 0
    const val PASSWORD: Byte = 1
    const val USER_PASSWORD: Byte = 2
    const val CLIENT_CERTIFICATE: Byte = 3
    const val CONNECTION_STRING: Byte = 4
    const val TOKEN: Byte = 5
}

/**
 * Encodes a secret as a current-version record.
 *
 * The returned array is plaintext and is the caller's to wipe — [Seal] does, in a
 * `finally`, the moment the cipher has taken it.
 */
internal fun encodeRecord(secret: SecretBundle): ByteArray {
    val fields = Fields()
    try {
        val kind = when (secret) {
            is SecretBundle.None -> Kind.NONE

            is SecretBundle.Password -> Kind.PASSWORD.also {
                fields.chars(secret.password)
            }

            is SecretBundle.UserPassword -> Kind.USER_PASSWORD.also {
                fields.text(secret.user)
                fields.chars(secret.password)
            }

            is SecretBundle.ClientCertificate -> Kind.CLIENT_CERTIFICATE.also {
                // Copied rather than referenced: `pack` does not own the caller's
                // key store, and `wipe` below would otherwise clear it.
                fields.bytes(secret.keyStore.copyOf())
                fields.chars(secret.passphrase)
            }

            is SecretBundle.ConnectionString -> Kind.CONNECTION_STRING.also {
                fields.chars(secret.value)
            }

            is SecretBundle.Token -> Kind.TOKEN.also {
                fields.chars(secret.value)
                // Absent rather than zero: an expiry of the epoch is a claim, and a
                // token that never expires is not making it.
                fields.text(secret.expiresAt?.toString().orEmpty())
            }
        }
        return fields.pack(RECORD_VERSION.toByte(), kind)
    } finally {
        fields.wipe()
    }
}

/**
 * Reads a record of any version this build understands.
 *
 * @throws MalformedEnvelopeException for a version, a kind, or a length this build
 *   cannot read. Never a partial secret: a record either decodes whole or fails.
 */
internal fun decodeRecord(plaintext: ByteArray): VaultRecord {
    if (plaintext.isEmpty()) throw MalformedEnvelopeException("the record is empty")
    return when (val version = plaintext[0].toInt()) {
        RECORD_VERSION_LEGACY -> VaultRecord(RECORD_VERSION_LEGACY, decodeLegacy(plaintext))
        RECORD_VERSION -> VaultRecord(RECORD_VERSION, decodeCurrent(plaintext))
        else -> throw MalformedEnvelopeException("unknown payload version $version")
    }
}

/**
 * A v1 record: everything after the version byte is the password, UTF-8.
 *
 * An empty one becomes [SecretBundle.None] rather than a password of no characters.
 * Nothing wrote that record — an empty password has always been stored as no record
 * at all — but the two are different statements and the migration rewrites whichever
 * it reads, so the reading must not invent an empty password to seal.
 */
private fun decodeLegacy(plaintext: ByteArray): SecretBundle {
    if (plaintext.size == 1) return SecretBundle.None
    val body = plaintext.copyOfRange(1, plaintext.size)
    try {
        return SecretBundle.Password(utf8ToChars(body))
    } finally {
        body.wipe()
    }
}

private fun decodeCurrent(plaintext: ByteArray): SecretBundle {
    if (plaintext.size < 2) throw MalformedEnvelopeException("the record names no kind")
    val cursor = Cursor(plaintext, offset = 2)
    val secret = when (val kind = plaintext[1]) {
        Kind.NONE -> SecretBundle.None
        Kind.PASSWORD -> SecretBundle.Password(cursor.chars())
        Kind.USER_PASSWORD -> SecretBundle.UserPassword(cursor.text(), cursor.chars())
        Kind.CLIENT_CERTIFICATE -> SecretBundle.ClientCertificate(cursor.bytes(), cursor.chars())
        Kind.CONNECTION_STRING -> SecretBundle.ConnectionString(cursor.chars())
        Kind.TOKEN -> SecretBundle.Token(
            value = cursor.chars(),
            expiresAt = cursor.text().takeIf { it.isNotEmpty() }?.let { stamp ->
                runCatching { Instant.parse(stamp) }
                    .getOrElse { throw MalformedEnvelopeException("the token's expiry is unreadable") }
            },
        )

        else -> throw MalformedEnvelopeException("unknown secret kind $kind")
    }
    // Trailing bytes mean this was written by something that knows a field this
    // build does not. Reading the prefix and ignoring the rest would hand a driver
    // a credential with a piece missing, so it is a failure.
    cursor.requireExhausted()
    return secret
}

/** Accumulates the fields of one record, then lays them out in a single allocation. */
private class Fields {
    private val parts = mutableListOf<ByteArray>()

    fun bytes(value: ByteArray) {
        parts += value
    }

    fun chars(value: CharArray) = bytes(charsToUtf8(value))

    fun text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))

    /**
     * One array, sized before anything is written into it.
     *
     * A growing buffer would leave each outgrown copy of the plaintext on the heap
     * for whatever reuses the page, which is the leak the whole class avoids.
     */
    fun pack(version: Byte, kind: Byte): ByteArray {
        val out = ByteArray(2 + parts.sumOf { LENGTH_BYTES + it.size })
        out[0] = version
        out[1] = kind
        var at = 2
        parts.forEach { part ->
            writeLength(out, at, part.size)
            at += LENGTH_BYTES
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** Clears every intermediate copy. The packed array belongs to the caller. */
    fun wipe() = parts.forEach { it.wipe() }
}

/** Reads length-prefixed fields, refusing a length that does not fit what is left. */
private class Cursor(private val source: ByteArray, private var offset: Int) {
    fun bytes(): ByteArray {
        if (offset + LENGTH_BYTES > source.size) throw MalformedEnvelopeException("the record ends inside a field")
        val length = readLength(source, offset)
        offset += LENGTH_BYTES
        // A negative or oversized length is a corrupted record, not an allocation.
        // Written as a subtraction on purpose: `offset + length > source.size`
        // overflows to a negative for a length near Int.MAX_VALUE and lets a
        // nine-byte record ask for two gigabytes, which arrives as an OutOfMemoryError
        // out of the unlock path rather than as a message about a damaged file.
        if (length < 0 || length > source.size - offset) {
            throw MalformedEnvelopeException("the record ends inside a field")
        }
        val field = source.copyOfRange(offset, offset + length)
        offset += length
        return field
    }

    fun chars(): CharArray {
        val field = bytes()
        try {
            return utf8ToChars(field)
        } finally {
            field.wipe()
        }
    }

    /** A field that is not a secret — a user name, a timestamp — so a `String` is fine. */
    fun text(): String = String(bytes(), Charsets.UTF_8)

    fun requireExhausted() {
        if (offset != source.size) throw MalformedEnvelopeException("the record carries an unrecognised field")
    }
}

/** Four bytes, big-endian, so a field longer than a key store can still be named. */
private const val LENGTH_BYTES = 4

private fun writeLength(out: ByteArray, at: Int, length: Int) {
    out[at] = (length ushr 24).toByte()
    out[at + 1] = (length ushr 16).toByte()
    out[at + 2] = (length ushr 8).toByte()
    out[at + 3] = length.toByte()
}

private fun readLength(source: ByteArray, at: Int): Int =
    (source[at].toInt() and 0xff shl 24) or
        (source[at + 1].toInt() and 0xff shl 16) or
        (source[at + 2].toInt() and 0xff shl 8) or
        (source[at + 3].toInt() and 0xff)
