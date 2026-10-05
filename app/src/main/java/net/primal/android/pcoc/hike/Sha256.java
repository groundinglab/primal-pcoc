package net.primal.android.pcoc.hike;

/**
 * Reused SHA-256 state. {@code update} and {@code digest} do not allocate.
 * One instance per seal loop; constructing a digest per seal is the slow port.
 * Checkpoint preimages are hashed by {@link CheckpointHash}, not here.
 */
final class Sha256 {

    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    };

    private final int[] words = new int[64];
    private final byte[] block = new byte[64];
    private int blockLength;
    private long totalBytes;
    private int h0;
    private int h1;
    private int h2;
    private int h3;
    private int h4;
    private int h5;
    private int h6;
    private int h7;

    Sha256() {
        reset();
    }

    void reset() {
        h0 = 0x6a09e667;
        h1 = 0xbb67ae85;
        h2 = 0x3c6ef372;
        h3 = 0xa54ff53a;
        h4 = 0x510e527f;
        h5 = 0x9b05688c;
        h6 = 0x1f83d9ab;
        h7 = 0x5be0cd19;
        blockLength = 0;
        totalBytes = 0L;
    }

    void update(byte[] data, int offset, int length) {
        totalBytes += length;
        if (blockLength > 0) {
            int take = Math.min(64 - blockLength, length);
            System.arraycopy(data, offset, block, blockLength, take);
            blockLength += take;
            offset += take;
            length -= take;
            if (blockLength == 64) {
                compress(block, 0);
                blockLength = 0;
            }
        }
        while (length >= 64) {
            compress(data, offset);
            offset += 64;
            length -= 64;
        }
        if (length > 0) {
            System.arraycopy(data, offset, block, 0, length);
            blockLength = length;
        }
    }

    /** Writes 32 digest bytes and resets this state. */
    void digest(byte[] out) {
        long bits = totalBytes << 3;
        block[blockLength++] = (byte) 0x80;
        if (blockLength > 56) {
            while (blockLength < 64) {
                block[blockLength++] = 0;
            }
            compress(block, 0);
            blockLength = 0;
        }
        while (blockLength < 56) {
            block[blockLength++] = 0;
        }
        for (int shift = 56; shift >= 0; shift -= 8) {
            block[blockLength++] = (byte) (bits >>> shift);
        }
        compress(block, 0);
        writeInt(out, 0, h0);
        writeInt(out, 4, h1);
        writeInt(out, 8, h2);
        writeInt(out, 12, h3);
        writeInt(out, 16, h4);
        writeInt(out, 20, h5);
        writeInt(out, 24, h6);
        writeInt(out, 28, h7);
        reset();
    }

    static byte[] hash(byte[] data) {
        Sha256 sha = new Sha256();
        sha.update(data, 0, data.length);
        byte[] out = new byte[32];
        sha.digest(out);
        return out;
    }

    private void compress(byte[] data, int offset) {
        int[] schedule = words;
        for (int i = 0; i < 16; i++) {
            int at = offset + (i << 2);
            schedule[i] = ((data[at] & 0xff) << 24)
                | ((data[at + 1] & 0xff) << 16)
                | ((data[at + 2] & 0xff) << 8)
                | (data[at + 3] & 0xff);
        }
        for (int i = 16; i < 64; i++) {
            int s0 = rotr(schedule[i - 15], 7) ^ rotr(schedule[i - 15], 18) ^ (schedule[i - 15] >>> 3);
            int s1 = rotr(schedule[i - 2], 17) ^ rotr(schedule[i - 2], 19) ^ (schedule[i - 2] >>> 10);
            schedule[i] = schedule[i - 16] + s0 + schedule[i - 7] + s1;
        }
        int a = h0;
        int b = h1;
        int c = h2;
        int d = h3;
        int e = h4;
        int f = h5;
        int g = h6;
        int h = h7;
        for (int i = 0; i < 64; i++) {
            int sigma1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            int ch = (e & f) ^ (~e & g);
            int temp1 = h + sigma1 + ch + K[i] + schedule[i];
            int sigma0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            int maj = (a & b) ^ (a & c) ^ (b & c);
            int temp2 = sigma0 + maj;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        h0 += a;
        h1 += b;
        h2 += c;
        h3 += d;
        h4 += e;
        h5 += f;
        h6 += g;
        h7 += h;
    }

    private static int rotr(int value, int bits) {
        return (value >>> bits) | (value << (32 - bits));
    }

    private static void writeInt(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }
}
