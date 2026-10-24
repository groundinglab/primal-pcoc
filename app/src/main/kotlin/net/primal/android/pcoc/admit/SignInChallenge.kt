package net.primal.android.pcoc.admit

import java.security.MessageDigest

/**
 * Shared `challenge_bytes`, integers big-endian:
 * `domain ‖ 0x00 ‖ uint8(n) ‖ origin[0..n) ‖ nonce[32] ‖ uint64_be(exp) ‖ tail[32]`.
 * Sign-in domain is `pcoc-gate-signin-v1` and the tail is `object_sha256`.
 * Flush domain is `pcoc-gate-flush-v1` and the tail is the login's x-only public key.
 */
object SignInChallenge {
    fun challengeBytes(
        origin: String,
        nonce: ByteArray,
        exp: ULong,
        objectSha256: ByteArray,
    ): ByteArray = gateChallengeBytes(
        domain = DOMAIN,
        origin = origin,
        nonce = nonce,
        exp = exp,
        tail = objectSha256,
    )

    /** Domain follows [SignInLink.kind]. [tail] is the object hash or the login public key. */
    fun challengeBytes(link: SignInLink, tail: ByteArray): ByteArray = gateChallengeBytes(
        domain = when (link.kind) {
            GateLinkKind.SignIn -> DOMAIN
            GateLinkKind.Flush -> FLUSH_DOMAIN
        },
        origin = link.origin,
        nonce = link.nonce(),
        exp = link.exp,
        tail = tail,
    )

    /** The BIP-340 message is `SHA-256(challenge_bytes)`, once. The signer must not hash it again. */
    fun bip340Message(challengeBytes: ByteArray): ByteArray = sha256(challengeBytes)

    internal val DOMAIN = "pcoc-gate-signin-v1".toByteArray(Charsets.US_ASCII)
    internal val FLUSH_DOMAIN = "pcoc-gate-flush-v1".toByteArray(Charsets.US_ASCII)
}

internal fun gateChallengeBytes(
    domain: ByteArray,
    origin: String,
    nonce: ByteArray,
    exp: ULong,
    tail: ByteArray,
): ByteArray {
    val originBytes = origin.toByteArray(Charsets.UTF_8)
    require(domain.isNotEmpty())
    require(originBytes.size in 1..MAX_ORIGIN_BYTES)
    require(nonce.size == FIELD_BYTES)
    require(tail.size == FIELD_BYTES)
    val out = ByteArray(domain.size + DOMAIN_SEPARATOR_BYTES + originBytes.size + FIELD_BYTES + U64_BYTES + FIELD_BYTES)
    var index = 0
    domain.copyInto(out, index)
    index += domain.size
    out[index++] = 0
    out[index++] = originBytes.size.toByte()
    originBytes.copyInto(out, index)
    index += originBytes.size
    nonce.copyInto(out, index)
    index += FIELD_BYTES
    for (shift in (U64_BYTES - 1) downTo 0) {
        out[index++] = (exp shr (shift * BYTE_BITS)).toByte()
    }
    tail.copyInto(out, index)
    return out
}

private const val FIELD_BYTES = 32
private const val U64_BYTES = 8
private const val BYTE_BITS = 8
private const val MAX_ORIGIN_BYTES = 255
private const val DOMAIN_SEPARATOR_BYTES = 2

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
