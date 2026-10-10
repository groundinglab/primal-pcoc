package net.primal.android.pcoc.hike

import net.primal.android.pcoc.bridge.PcocNative

/**
 * Reference `public_key` when the bytes yield one.
 * This does not verify the hike, and enroll does not call it.
 */
internal fun hikeObjectPublicKey(bytes: ByteArray): ByteArray? {
    val key = try {
        PcocNative.yieldedPublicKey(bytes)
    } catch (error: UnsatisfiedLinkError) {
        null
    } catch (error: Exception) {
        null
    }
    return key?.takeIf { it.size == PUBLIC_KEY_LEN }?.copyOf()
}

private const val PUBLIC_KEY_LEN = 32
