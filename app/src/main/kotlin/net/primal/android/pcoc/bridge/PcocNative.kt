package net.primal.android.pcoc.bridge

/** Live portable index. Verify reads this origin. The SDK does not name a default index. */
internal const val INDEX_URL = "https://index.groundinglab.io"

/** Live Bari. This SDK build does not post to it. */
internal const val BARI_URL = "https://bari.groundinglab.io"

/**
 * Static methods on this object are the JNI exports in `pcoc_bridge`.
 * `PCOC_BRIDGE_LIB` loads a host build for unit tests. The APK uses the
 * packaged library.
 */
internal object PcocNative {
    init {
        val path = System.getenv("PCOC_BRIDGE_LIB")
        if (path.isNullOrEmpty()) {
            System.loadLibrary("pcoc_bridge")
        } else {
            System.load(path)
        }
    }

    @JvmStatic
    external fun indexUrl(): String

    @JvmStatic
    external fun bariUrl(): String

    /** JSON: `record`, `unreadable`, or `engine`. */
    @JvmStatic
    external fun verify(objectBytes: ByteArray, indexUrl: String, threads: Int): String

    /** 32-byte `public_key` when the bytes yield one. Null otherwise. */
    @JvmStatic
    external fun yieldedPublicKey(objectBytes: ByteArray): ByteArray?

    @JvmStatic
    external fun startProduce(
        publicKeyHex: String,
        outputPath: String,
        gossipPeer: String,
        listener: ProduceListener,
    )

    @JvmStatic
    external fun stopProduce()
}

/** Called on the produce thread. One JSON object per event. */
internal fun interface ProduceListener {
    fun onEvent(json: String)
}
