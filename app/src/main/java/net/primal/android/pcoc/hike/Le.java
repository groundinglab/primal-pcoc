package net.primal.android.pcoc.hike;

final class Le {

    private Le() {
    }

    static long u64(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
            | ((bytes[offset + 1] & 0xffL) << 8)
            | ((bytes[offset + 2] & 0xffL) << 16)
            | ((bytes[offset + 3] & 0xffL) << 24)
            | ((bytes[offset + 4] & 0xffL) << 32)
            | ((bytes[offset + 5] & 0xffL) << 40)
            | ((bytes[offset + 6] & 0xffL) << 48)
            | ((bytes[offset + 7] & 0xffL) << 56);
    }

    static void put(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
        bytes[offset + 4] = (byte) (value >>> 32);
        bytes[offset + 5] = (byte) (value >>> 40);
        bytes[offset + 6] = (byte) (value >>> 48);
        bytes[offset + 7] = (byte) (value >>> 56);
    }

    static byte[] copy32(byte[] bytes) {
        byte[] out = new byte[32];
        System.arraycopy(bytes, 0, out, 0, 32);
        return out;
    }
}
