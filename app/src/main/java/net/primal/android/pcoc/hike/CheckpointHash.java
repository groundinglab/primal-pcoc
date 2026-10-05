package net.primal.android.pcoc.hike;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of one checkpoint preimage ({@link HikeConst#CHECKPOINT_PREIMAGE_LEN} bytes).
 * The million-step seal keeps {@link Sha256}: a provider digest per step is the slow port.
 * This bulk hash uses the platform digest, which is native on Android, and falls back to
 * {@link Sha256} when that digest is missing or rejects the output buffer.
 */
final class CheckpointHash {

    private final MessageDigest platform;
    private final Sha256 portable;

    CheckpointHash(MessageDigest platform, Sha256 portable) {
        this.platform = platform;
        this.portable = portable;
    }

    static CheckpointHash open() {
        MessageDigest platform;
        try {
            platform = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ignored) {
            platform = null;
        }
        if (platform != null && platform.getDigestLength() != 32) {
            platform = null;
        }
        return new CheckpointHash(platform, new Sha256());
    }

    boolean usesPlatform() {
        return platform != null;
    }

    void digest(byte[] preimage, byte[] out) {
        if (platform != null) {
            platform.reset();
            platform.update(preimage, 0, preimage.length);
            try {
                if (platform.digest(out, 0, 32) == 32) {
                    return;
                }
            } catch (DigestException ignored) {
                // The portable digest covers a provider that rejects the output buffer.
            }
        }
        portable.reset();
        portable.update(preimage, 0, preimage.length);
        portable.digest(out);
    }
}
