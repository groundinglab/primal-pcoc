package net.primal.android.pcoc.admit

internal fun cborPublicKeyMap(publicKey: ByteArray): ByteArray {
    require(publicKey.size == PUBLIC_KEY_LEN)
    val name = "public_key".toByteArray(Charsets.UTF_8)
    val out = ByteArray(1 + 1 + name.size + 2 + publicKey.size)
    var index = 0
    out[index++] = 0xa1.toByte()
    out[index++] = (0x60 or name.size).toByte()
    name.copyInto(out, index)
    index += name.size
    out[index++] = 0x58
    out[index++] = publicKey.size.toByte()
    publicKey.copyInto(out, index)
    return out
}

/** A canonical §15.1 link. The link does not name an object. */
internal fun linkFor(
    nonceHex: String = "0123456789abcdef".repeat(4),
    exp: ULong = 1_893_456_000uL,
): SignInLink {
    val text = "https://groundinglab.io/sign-in/?v=1&nonce=$nonceHex&exp=$exp"
    return (SignInLinks.parse(text) as SignInLinkParse.Ready).link
}

private const val PUBLIC_KEY_LEN = 32
