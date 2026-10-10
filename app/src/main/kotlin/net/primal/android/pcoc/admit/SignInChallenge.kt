package net.primal.android.pcoc.admit

import java.security.MessageDigest

/**
 * SPEC §15.1 `challenge_bytes`, integers big-endian:
 * `"pcoc-gate-signin-v1" ‖ 0x00 ‖ uint8(n) ‖ origin[0..n) ‖ nonce[32] ‖ uint64_be(exp) ‖ object_sha256[32]`.
 */
object SignInChallenge {
    fun challengeBytes(
        origin: String,
        nonce: ByteArray,
        exp: ULong,
        objectSha256: ByteArray,
    ): ByteArray {
        val originBytes = origin.toByteArray(Charsets.UTF_8)
        require(originBytes.size in 1..MAX_ORIGIN_BYTES)
        require(nonce.size == FIELD_BYTES)
        require(objectSha256.size == FIELD_BYTES)
        val out = ByteArray(FIXED_BYTES + originBytes.size)
        var index = 0
        DOMAIN.copyInto(out, index)
        index += DOMAIN.size
        out[index++] = 0
        out[index++] = originBytes.size.toByte()
        originBytes.copyInto(out, index)
        index += originBytes.size
        nonce.copyInto(out, index)
        index += FIELD_BYTES
        for (shift in (U64_BYTES - 1) downTo 0) {
            out[index++] = (exp shr (shift * BYTE_BITS)).toByte()
        }
        objectSha256.copyInto(out, index)
        return out
    }

    fun challengeBytes(link: SignInLink, objectSha256: ByteArray): ByteArray =
        challengeBytes(
            origin = link.origin,
            nonce = link.nonce(),
            exp = link.exp,
            objectSha256 = objectSha256,
        )

    /** The BIP-340 message is `SHA-256(challenge_bytes)`, once. The signer must not hash it again. */
    fun bip340Message(challengeBytes: ByteArray): ByteArray = sha256(challengeBytes)

    internal val DOMAIN = "pcoc-gate-signin-v1".toByteArray(Charsets.US_ASCII)
    private const val FIELD_BYTES = 32
    private const val U64_BYTES = 8
    private const val BYTE_BITS = 8
    private const val MAX_ORIGIN_BYTES = 255
    private val FIXED_BYTES = DOMAIN.size + 2 + FIELD_BYTES + U64_BYTES + FIELD_BYTES
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
