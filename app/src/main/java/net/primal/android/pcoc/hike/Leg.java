package net.primal.android.pcoc.hike;

/** Section 11. The seal loop reuses one SHA-256 state and one 20-byte preimage. */
final class Leg {

    private Leg() {
    }

    static long seal(Sha256 sha, byte[] preimage, byte[] digest, long folded) {
        Le.put(preimage, 12, folded);
        sha.reset();
        sha.update(preimage, 0, 20);
        sha.digest(digest);
        return Le.u64(digest, 0);
    }

    static long step(byte[] terrain, long state, Sha256 sha, byte[] preimage, byte[] digest) {
        int slot = (int) Long.remainderUnsigned(state, HikeConst.N_SLOTS);
        int start = slot * HikeConst.SLOT_BYTES;
        long folded = state;
        for (int word = 0; word < HikeConst.WORDS_PER_SLOT; word++) {
            long value = Le.u64(terrain, start + (word << 3));
            folded ^= value ^ (long) word;
        }
        return seal(sha, preimage, digest, folded);
    }

    static long legResult(byte[] terrain, byte[] previousCheckpoint) throws HikeUnfinished {
        Sha256 sha = new Sha256();
        byte[] preimage = new byte[20];
        System.arraycopy(HikeConst.SEAL_DOMAIN, 0, preimage, 0, 12);
        byte[] digest = new byte[32];
        long state = Le.u64(previousCheckpoint, 0);
        for (int step = 0; step < HikeConst.STEPS_PER_LEG; step++) {
            if ((step & STOP_MASK) == 0 && Thread.currentThread().isInterrupted()) {
                throw new HikeUnfinished();
            }
            state = step(terrain, state, sha, preimage, digest);
        }
        return state;
    }

    private static final int STOP_MASK = 8191;
}
