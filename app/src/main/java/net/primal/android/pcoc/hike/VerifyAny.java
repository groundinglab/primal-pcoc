package net.primal.android.pcoc.hike;

/**
 * The executing door. After section 14 and section 15 finish, the word is
 * {@link Word#VALID} or {@link Word#INVALID}. Terrain allocation failure and an
 * unfinished six-thread pool return null: neither word.
 */
public final class VerifyAny {

    public enum Word {
        VALID,
        INVALID
    }

    public static Word finish(byte[] object, IndexSource index, Draw.Entropy entropy) throws IndexUnreachable {
        return finish(object, index, entropy, null);
    }

    public static Word finish(
        byte[] object,
        IndexSource index,
        Draw.Entropy entropy,
        VerifyProgress progress
    ) throws IndexUnreachable {
        try {
            HikeVerify.Measurements measurements = HikeVerify.verify(object, index, entropy, progress);
            return HikeVerify.admit(measurements) ? Word.VALID : Word.INVALID;
        } catch (HikeUnfinished unfinished) {
            return null;
        } catch (OutOfMemoryError allocation) {
            return null;
        }
    }

    public static Word finish(byte[] object, IndexSource index) throws IndexUnreachable {
        return finish(object, index, new OsEntropy(), null);
    }

    private VerifyAny() {
    }
}
