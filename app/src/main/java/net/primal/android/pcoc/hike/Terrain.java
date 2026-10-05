package net.primal.android.pcoc.hike;

/** Section 10. Fill stays on the calling thread. The terrain is not shrunk. */
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

    static long rotl(long word, int bits) {
        int rotate = bits & 63;
        if (rotate == 0) {
            return word;
        }
        return (word << rotate) | (word >>> (64 - rotate));
    }

    /** {@code TERRAIN_BYTES} bytes. Throws {@link OutOfMemoryError} rather than shrinking. */
    static byte[] fill(long fillPin) {
        byte[] terrain = new byte[HikeConst.TERRAIN_BYTES];
        long[] words = new long[HikeConst.WORDS_PER_SLOT];
        int[] parents = new int[3];
        for (int slot = 0; slot < HikeConst.N_SLOTS; slot++) {
            for (int word = 0; word < HikeConst.WORDS_PER_SLOT; word++) {
                long mixed = fillPin ^ ((long) slot << 32) ^ (long) word ^ HikeConst.WORD_XOR;
                words[word] = mix64(mixed);
            }
            if (slot > 0) {
                parents[0] = slot - 1;
                parents[1] = bitrevParent(slot);
                parents[2] = mulParent(slot);
                for (int parentNumber = 0; parentNumber < 3; parentNumber++) {
                    long k = (long) parentNumber + 1L;
                    long kTerm = k * HikeConst.PARENT_MUL;
                    int base = parents[parentNumber] * HikeConst.SLOT_BYTES;
                    for (int word = 0; word < HikeConst.WORDS_PER_SLOT; word++) {
                        long parentWord = Le.u64(terrain, base + (word << 3));
                        int rotate = (word + (int) k) & 63;
                        long mixed = words[word] ^ rotl(parentWord, rotate);
                        words[word] = mix64(mixed ^ kTerm);
                    }
                }
                long slotMix = (long) slot * HikeConst.SLOT_MUL;
                for (int word = 0; word < HikeConst.WORDS_PER_SLOT; word++) {
                    words[word] = mix64(words[word] ^ slotMix);
                }
            }
            int base = slot * HikeConst.SLOT_BYTES;
            for (int word = 0; word < HikeConst.WORDS_PER_SLOT; word++) {
                Le.put(terrain, base + (word << 3), words[word]);
            }
        }
        return terrain;
    }
}
