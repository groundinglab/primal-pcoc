package net.primal.android.pcoc.hike;

/**
 * Section 10. The fill stays on the calling thread. The room is not shrunk.
 * Words stay in a {@code long[]} so the fill does not touch one byte at a time.
 */
final class Terrain {

    private Terrain() {
    }

    static long mix64(long x) {
        long y = x + HikeConst.MIX_ADD;
        y = (y ^ (y >>> 30)) * HikeConst.MIX_MUL1;
        y = (y ^ (y >>> 27)) * HikeConst.MIX_MUL2;
        return y ^ (y >>> 31);
    }

    static long fillPin(byte[] productionSeed) {
        long x = Le.u64(HikeConst.SEED_DOMAIN, 0);
        for (int i = 0; i < 8; i++) {
            long lane = Le.u64(productionSeed, i * 8);
            x = mix64(x ^ lane ^ ((long) i * HikeConst.MIX_ADD));
        }
        return x;
    }

    static byte[] productionSeed(byte[] publicKey, byte[] seedBeacon) {
        byte[] out = new byte[64];
        System.arraycopy(publicKey, 0, out, 0, 32);
        System.arraycopy(seedBeacon, 0, out, 32, 32);
        return out;
    }

    static int bitrevParent(int slot) {
        int bits = Math.max(31 - Integer.numberOfLeadingZeros(slot), 1);
        int reversed = Integer.reverse(slot) >>> (32 - bits);
        return reversed % slot;
    }

    static int mulParent(int slot) {
        long mixed = (slot & 0xffffffffL) * HikeConst.MIX_ADD;
        return (int) Long.remainderUnsigned(mixed >>> 32, slot & 0xffffffffL);
    }

    /**
     * {@code TERRAIN_BYTES} bytes. Throws {@link OutOfMemoryError} rather than shrinking.
     * The returned room is the only reference to that array.
     */
    static TerrainRoom fill(long fillPin) throws HikeUnfinished {
        long[] words = new long[HikeConst.N_SLOTS * HikeConst.WORDS_PER_SLOT];
        try {
            write(words, fillPin);
            return new TerrainRoom(words);
        } catch (HikeUnfinished unfinished) {
            words = null;
            throw unfinished;
        } catch (OutOfMemoryError allocation) {
            words = null;
            throw allocation;
        }
    }

    private static void write(long[] terrain, long fillPin) throws HikeUnfinished {
        for (int slot = 0; slot < HikeConst.N_SLOTS; slot++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new HikeUnfinished();
            }
            fillSlot(terrain, slot, fillPin);
        }
    }

    /** One slot. Called once per slot so the runtime can compile this loop. */
    private static void fillSlot(long[] terrain, int slot, long fillPin) {
        int base = slot * HikeConst.WORDS_PER_SLOT;
        long slotShift = ((long) slot) << 32;
        for (int word = 0; word < HikeConst.WORDS_PER_SLOT; word++) {
            terrain[base + word] = mix64(fillPin ^ slotShift ^ (long) word ^ HikeConst.WORD_XOR);
        }
        if (slot == 0) {
            return;
        }
        mixParent(terrain, base, (slot - 1) * HikeConst.WORDS_PER_SLOT, 1);
        mixParent(terrain, base, bitrevParent(slot) * HikeConst.WORDS_PER_SLOT, 2);
        mixParent(terrain, base, mulParent(slot) * HikeConst.WORDS_PER_SLOT, 3);
        long slotMix = (long) slot * HikeConst.SLOT_MUL;
        for (int word = 0; word < HikeConst.WORDS_PER_SLOT; word++) {
            terrain[base + word] = mix64(terrain[base + word] ^ slotMix);
        }
    }

    private static void mixParent(long[] terrain, int base, int parentBase, int k) {
        long kTerm = (long) k * HikeConst.PARENT_MUL;
        for (int word = 0; word < HikeConst.WORDS_PER_SLOT; word++) {
            long parentWord = terrain[parentBase + word];
            int rotate = (word + k) & 63;
            long mixed = terrain[base + word] ^ Long.rotateLeft(parentWord, rotate);
            terrain[base + word] = mix64(mixed ^ kTerm);
        }
    }
}
