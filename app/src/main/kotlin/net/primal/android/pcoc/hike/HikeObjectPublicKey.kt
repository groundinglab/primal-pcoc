package net.primal.android.pcoc.hike

import net.primal.core.utils.runCatching

/**
 * Top-level `public_key` when the bytes parse as a hike object.
 * This does not verify the hike, and enroll does not call it.
 */
internal fun hikeObjectPublicKey(bytes: ByteArray): ByteArray? {
    val measured = runCatching { HikeObject.readMeasured(bytes) }.getOrNull() ?: return null
    val key = measured.hike.publicKey
    return if (key.size == PUBLIC_KEY_LEN) key.copyOf() else null
}

private const val PUBLIC_KEY_LEN = 32
