package dev.caracal.core.vault

/**
 * The vault's two stores with no SQLite behind them: its contract is a map and a
 * list, and every property Phase 4 claims about migration is a property of those.
 *
 * [failReplaceAfter] is the injected crash. Kill -9 is not something a test can do,
 * so what stands in for it is the store refusing the write that a dying process
 * would not have completed — same observable state afterwards, and the assertion is
 * the same one either way: whatever is on disk still opens.
 */
internal class FakeVaultStore : MetadataStore, SealedSecretStore {
    val values = mutableMapOf<String, ByteArray>()
    val sealed = linkedMapOf<SecretIdentity, ByteArray>()

    /** After this many successful rewrites, the next one throws. Null never throws. */
    var failReplaceAfter: Int? = null

    var replacements = 0
        private set

    override suspend fun getMetadata(key: String): ByteArray? = values[key]

    override suspend fun putMetadata(key: String, value: ByteArray) {
        values[key] = value
    }

    override suspend fun sealedSecrets(): List<SealedSecret> =
        sealed.map { (identity, envelope) -> SealedSecret(identity, envelope) }

    override suspend fun replaceSealedSecret(identity: SecretIdentity, envelope: ByteArray) {
        failReplaceAfter?.let { limit ->
            if (replacements >= limit) throw IllegalStateException("the process died here")
        }
        sealed[identity] = envelope
        replacements++
    }
}
