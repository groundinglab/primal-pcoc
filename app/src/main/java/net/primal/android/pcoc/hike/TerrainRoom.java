package net.primal.android.pcoc.hike;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
        copyBytes(slot * HikeConst.SLOT_BYTES, dest);
    }

    /** Copies {@code dest.length} bytes starting at a byte offset. The offset need not be aligned. */
    void copyBytes(int offset, byte[] dest) {
        copyBytes(offset, dest, 0, dest.length);
    }

    /** Copies room bytes into a range of {@code dest}. The room offset need not be aligned. */
    void copyBytes(int offset, byte[] dest, int destOffset, int length) {
        if (offset < 0 || length < 0 || length > HikeConst.TERRAIN_BYTES
            || offset > HikeConst.TERRAIN_BYTES - length) {
            throw new IllegalArgumentException("sample is outside the terrain");
        }
        if (destOffset < 0 || length > dest.length || destOffset > dest.length - length) {
            throw new IllegalArgumentException("destination is too small");
        }
        int wordIndex = offset >>> 3;
        int shift = offset & 7;
        int written = 0;
        if (shift != 0) {
            long value = words[wordIndex];
            int take = Math.min(8 - shift, length);
            for (int bit = 0; bit < take; bit++) {
                dest[destOffset + bit] = (byte) (value >>> ((shift + bit) << 3));
            }
            written = take;
            wordIndex++;
        }
        int full = (length - written) >>> 3;
        if (full > 0) {
            ByteBuffer.wrap(dest, destOffset + written, full << 3)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asLongBuffer()
                .put(words, wordIndex, full);
        }
        written += full << 3;
        wordIndex += full;
        int remain = length - written;
        if (remain > 0) {
            long value = words[wordIndex];
            for (int bit = 0; bit < remain; bit++) {
                dest[destOffset + written + bit] = (byte) (value >>> (bit << 3));
            }
        }
    }
}
