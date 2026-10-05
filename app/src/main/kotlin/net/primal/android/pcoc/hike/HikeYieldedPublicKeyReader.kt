package net.primal.android.pcoc.hike

/**
 * Reads `hike::yielded_public_key` only. Does not call `object_public_key` and does not
 * decide that the bytes are a hike.
 */
fun interface HikeYieldedPublicKeyReader {
    fun yieldedPublicKey(objectBytes: ByteArray): YieldedPublicKey
}

/**
 * Calls the native reader when [linked] is true. When it is false, throws
 * [HikeReaderUnavailable] and does not invent a key.
 */
class LinkedHikeYieldedPublicKeyReader(
    private val linked: Boolean,
    private val nativeRead: (ByteArray) -> ByteArray,
) : HikeYieldedPublicKeyReader {

    override fun yieldedPublicKey(objectBytes: ByteArray): YieldedPublicKey {
        if (!linked) {
            throw HikeReaderUnavailable("hike::yielded_public_key is not linked on this build.")
        }
        val packed = try {
            nativeRead(objectBytes)
        } catch (error: UnsatisfiedLinkError) {
            throw HikeReaderUnavailable("hike::yielded_public_key failed to load.", error)
        }
        return decodeYieldedPublicKey(packed)
    }
}

internal object HikeYieldedPublicKeyNative {
    @Volatile
    private var loaded = false

    fun read(objectBytes: ByteArray): ByteArray {
        ensureLoaded()
        return HikeYieldedPublicKeyJni.nativeYieldedPublicKey(objectBytes)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary(LIBRARY_NAME)
                loaded = true
            }
        }
    }

    private const val LIBRARY_NAME = "hike_yielded_public_key"
}
