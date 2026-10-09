package net.primal.android.pcoc.admit

import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.bechToBytesOrThrow
import net.primal.domain.nostr.cryptography.utils.toHex

internal const val PRODUCER_KEY_SIZE = 32
private const val MESSAGE_SIZE = 32
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
 * BIP-340 over `SHA-256(challenge_bytes)` via [CryptoUtils.sign], which signs its 32-byte input as the message.
 * Not the link text, not a header hash, and not Nostr `signOrThrow`.
 */
object AdmissionSigner {
    fun sign(privateKey: ByteArray, link: SignInLink): String =
        signMessage(
            privateKey = privateKey,
            message = SignInChallenge.bip340Message(SignInChallenge.challengeBytes(link)),
        )

    fun signMessage(privateKey: ByteArray, message: ByteArray): String {
        require(privateKey.size == PRODUCER_KEY_SIZE)
        require(message.size == MESSAGE_SIZE) { "the BIP-340 message is the 32-byte SHA-256 of challenge_bytes" }
        val signature = CryptoUtils.sign(data = message, privateKey = privateKey)
        require(signature.size == SCHNORR_SIGNATURE_SIZE)
        return signature.toHex().lowercase()
    }
}
