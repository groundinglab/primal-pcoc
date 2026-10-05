package net.primal.android.settings.groundinglab

import fr.acinq.secp256k1.Hex
import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.bechToBytesOrThrow
import net.primal.domain.nostr.cryptography.utils.toHex

private const val PRIVATE_KEY_SIZE = 32
private const val TIP_HASH_SIZE = 32
private const val SCHNORR_SIGNATURE_SIZE = 64

/**
 * Producer nsec only. npub-only, external signer, and the internal signer are not this key.
 */
fun Credential.producerPrivateKeyOrNull(): ByteArray? {
    val encoded = nsec
    if (type != CredentialType.PrivateKey || encoded == null) return null
    return runCatching { encoded.bechToBytesOrThrow(hrp = "nsec") }
        .getOrNull()
        ?.takeIf { it.size == PRIVATE_KEY_SIZE }
}

/**
 * BIP-340 over the 32 display-order tip-hash bytes via [CryptoUtils.sign] (`secp256k1.signSchnorr`).
 * This is not a Nostr event signature. Do not route it through `NostrNotary` or `signOrThrow`.
 */
object TipHashSigner {

    fun sign(privateKey: ByteArray, tipHashHex: String): String {
        require(privateKey.size == PRIVATE_KEY_SIZE)
        val hash = Hex.decode(tipHashHex)
        require(hash.size == TIP_HASH_SIZE) { "tip hash must be 32 display-order bytes" }
        val signature = CryptoUtils.sign(data = hash, privateKey = privateKey)
        require(signature.size == SCHNORR_SIGNATURE_SIZE)
        return signature.toHex().lowercase()
    }
}
