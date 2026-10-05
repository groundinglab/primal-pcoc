package net.primal.android.pcoc.hike;

/**
 * One terrain room: {@link HikeConst#TERRAIN_BYTES} bytes stored as little-endian words.
 * The caller releases it. A later run must not keep this array.
 */
final class TerrainRoom {

    private long[] words;

    TerrainRoom(long[] words) {
        this.words = words;
    }

    /** A room the draw-failure paths never read. */
    static TerrainRoom unused() {
        return new TerrainRoom(null);
    }

    void release() {
        words = null;
    }

    long[] array() {
        return words;
    }

    long word(int slot, int index) {
        return words[(slot * HikeConst.WORDS_PER_SLOT) + index];
    }

    void copySlot(int slot, byte[] dest) {
        int base = slot * HikeConst.WORDS_PER_SLOT;
        for (int word = 0; word < HikeConst.WORDS_PER_SLOT; word++) {
            Le.put(dest, word << 3, words[base + word]);
        }
    }

    /** Copies {@code dest.length} bytes starting at a byte offset. The offset need not be aligned. */
    void copyBytes(int offset, byte[] dest) {
        int length = dest.length;
        if (offset < 0 || length < 0 || offset > HikeConst.TERRAIN_BYTES - length) {
            throw new IllegalArgumentException("sample is outside the terrain");
        }
        int wordIndex = offset >>> 3;
        int shift = offset & 7;
        int written = 0;
        if (shift != 0) {
            long value = words[wordIndex];
            int take = Math.min(8 - shift, length);
            for (int bit = 0; bit < take; bit++) {
                dest[bit] = (byte) (value >>> ((shift + bit) << 3));
            }
            written = take;
            wordIndex++;
        }
        int full = (length - written) >>> 3;
        for (int i = 0; i < full; i++) {
            Le.put(dest, written + (i << 3), words[wordIndex + i]);
        }
        written += full << 3;
        wordIndex += full;
        int remain = length - written;
        if (remain > 0) {
            long value = words[wordIndex];
            for (int bit = 0; bit < remain; bit++) {
                dest[written + bit] = (byte) (value >>> (bit << 3));
            }
        }
    }
}
