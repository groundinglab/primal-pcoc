package net.primal.android.pcoc.hike;

/**
 * Thin JNI call into {@code hike::yielded_public_key}. This is not {@code object_public_key}.
 *
 * <p>Drop {@code libhike_yielded_public_key.so} under {@code app/src/main/jniLibs/<abi>/}. The next
 * build sets {@code BuildConfig.PCOC_HIKE_READER_LINKED} and {@code System.loadLibrary} runs before
 * this method is called. Until that artifact exists, the Kotlin reader does not call this method
 * and does not invent a key.
 *
 * <p>JNI symbol:
 * {@code Java_net_primal_android_pcoc_hike_HikeYieldedPublicKeyJni_nativeYieldedPublicKey}
 *
 * <p>Return bytes, first byte is the discriminant:
 * <ul>
 *   <li>{@code 1} followed by exactly 32 key bytes — PublicKey</li>
 *   <li>{@code 2} alone — NoPublicKey</li>
 *   <li>{@code 3} alone — Undecodable</li>
 * </ul>
 * Any other shape is a binding error. Do not collapse it into NoPublicKey or Undecodable.
 */
public final class HikeYieldedPublicKeyJni {
    private HikeYieldedPublicKeyJni() {
    }

    public static native byte[] nativeYieldedPublicKey(byte[] objectBytes);
}
