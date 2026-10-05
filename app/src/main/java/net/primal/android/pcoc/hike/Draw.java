package net.primal.android.pcoc.hike;

/** Section 14.6. Comparisons on the draw are unsigned. A signed {@code long} compare is wrong. */
final class Draw {

    static final class Unavailable extends Exception {
    }

    /** Supplies the next bytes, or returns false when it cannot. */
    public interface Entropy {
        boolean fill(byte[] dest);
    }

    static long[] distinct(long count, int take, Entropy entropy) throws Unavailable {
        if (count == 0L || Long.compareUnsigned(take, count) > 0) {
            throw new Unavailable();
        }
        long[] chosen = new long[take];
        int filled = 0;
        byte[] buffer = new byte[8];
        while (filled < take) {
            long index = one(count, entropy, buffer);
            boolean seen = false;
            for (int i = 0; i < filled; i++) {
                if (chosen[i] == index) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                chosen[filled++] = index;
            }
        }
        return chosen;
    }

    static long one(long count, Entropy entropy, byte[] buffer) throws Unavailable {
        if (count == 0L) {
            throw new Unavailable();
        }
        long residue = modulusResidue(count);
        while (true) {
            if (!entropy.fill(buffer)) {
                throw new Unavailable();
            }
            long draw = Le.u64(buffer, 0);
            if (residue == 0L) {
                return Long.remainderUnsigned(draw, count);
            }
            long limit = -residue;
            if (Long.compareUnsigned(draw, limit) < 0) {
                return Long.remainderUnsigned(draw, count);
            }
        }
    }

    /** {@code 2^64 mod n}, or 0 when {@code n} is a power of two (and when {@code n == 1}). */
    static long modulusResidue(long count) {
        long remainder = Long.remainderUnsigned(-1L, count);
        if (remainder == count - 1L) {
            return 0L;
        }
        return remainder + 1L;
    }

    private Draw() {
    }
}
