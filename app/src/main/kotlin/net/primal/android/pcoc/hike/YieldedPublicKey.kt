package net.primal.android.pcoc.hike

/**
 * The three results of `hike::yielded_public_key`. There is no fourth result.
 * A PublicKey is the 32-byte compare input. It is not proof the file is a hike.
 */
sealed class YieldedPublicKey {
    class PublicKey(val bytes: ByteArray) : YieldedPublicKey() {
        init {
            require(bytes.size == ACCOUNT_KEY_SIZE)
        }

        override fun equals(other: Any?): Boolean = other is PublicKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object NoPublicKey : YieldedPublicKey()

    data object Undecodable : YieldedPublicKey()
}

class HikeReaderUnavailable(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal const val ACCOUNT_KEY_SIZE = 32
internal const val YIELDED_TAG_PUBLIC_KEY = 1
internal const val YIELDED_TAG_NO_PUBLIC_KEY = 2
internal const val YIELDED_TAG_UNDECODABLE = 3

/**
 * Maps the JNI discriminant. A malformed or unknown tag is [HikeReaderUnavailable],
 * not [YieldedPublicKey.NoPublicKey] and not [YieldedPublicKey.Undecodable].
 */
internal fun decodeYieldedPublicKey(packed: ByteArray): YieldedPublicKey {
    val tag = packed.firstOrNull()?.toInt()?.and(BYTE_MASK)
    val publicKey = if (tag == YIELDED_TAG_PUBLIC_KEY && packed.size == PUBLIC_KEY_PACKED_SIZE) {
        packed.copyOfRange(1, packed.size)
    } else {
        null
    }
    return when {
        publicKey != null -> YieldedPublicKey.PublicKey(publicKey)
        tag == YIELDED_TAG_NO_PUBLIC_KEY && packed.size == TAG_ONLY_SIZE -> YieldedPublicKey.NoPublicKey
        tag == YIELDED_TAG_UNDECODABLE && packed.size == TAG_ONLY_SIZE -> YieldedPublicKey.Undecodable
        else -> throw HikeReaderUnavailable("hike::yielded_public_key returned an unrecognized result.")
    }
}

private const val BYTE_MASK = 0xFF
private const val TAG_ONLY_SIZE = 1
private const val PUBLIC_KEY_PACKED_SIZE = 33
