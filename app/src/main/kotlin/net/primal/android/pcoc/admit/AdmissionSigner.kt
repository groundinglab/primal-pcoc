package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.bechToBytesOrThrow
import net.primal.domain.nostr.cryptography.utils.toHex

internal const val PRODUCER_KEY_SIZE = 32
private const val SCHNORR_SIGNATURE_SIZE = 64

/** X-only public key of the nsec that will sign. Not logged. */
internal fun signingPublicKey(privateKey: ByteArray): ByteArray? {
    if (privateKey.size != PRODUCER_KEY_SIZE) return null
    val created = runCatching { CryptoUtils.publicKeyCreate(privateKey) }.getOrNull()
    return created?.takeIf { it.size == PRODUCER_KEY_SIZE }
}

/**
 * Producer nsec only. npub-only, the internal signer, and an external signer are not this key.
 * The decoded bytes are not logged.
 */
fun Credential.admissionPrivateKeyOrNull(): ByteArray? {
    val encoded = nsec
    if (type != CredentialType.PrivateKey || encoded == null) return null
    return runCatching { encoded.bechToBytesOrThrow(hrp = "nsec") }
        .getOrNull()
        ?.takeIf { it.size == PRODUCER_KEY_SIZE }
}

/**
 * BIP-340 over the 32 display-order hash bytes via [CryptoUtils.sign].
 * Not the hex text, not a height, not the URL, and not Nostr `signOrThrow`.
 */
object AdmissionSigner {
    fun sign(privateKey: ByteArray, hashHex: String): String {
        require(privateKey.size == PRODUCER_KEY_SIZE)
        require(HASH_HEX.matches(hashHex)) { "signed message must be the 32 bytes of h" }
        val hash = Hex.decode(hashHex)
        require(hash.size == PRODUCER_KEY_SIZE) { "signed message must be the 32 bytes of h" }
        val signature = CryptoUtils.sign(data = hash, privateKey = privateKey)
        require(signature.size == SCHNORR_SIGNATURE_SIZE)
        return signature.toHex().lowercase()
    }
}

private val HASH_HEX = Regex("""^[0-9a-fA-F]{64}$""")
