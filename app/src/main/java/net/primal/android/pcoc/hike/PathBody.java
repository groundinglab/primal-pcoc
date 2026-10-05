package net.primal.android.pcoc.hike;

import java.util.Arrays;

/**
 * Canonical path body. Byte layout and {@code validate_for} follow
 * {@code services/bari/src/proof.rs} as copied in {@code reference/hike}.
 */
final class PathBody {

    static final int PROOF_BODY_VERSION = 0x01;
    private static final int MAX_SIBLINGS = 32;
    private static final int FIXED_BODY_BYTES = 146;
    private static final byte LEAF_DOMAIN = 0x1A;
    private static final byte PARENT_DOMAIN = 0x1B;

    static final class Locators {
        final byte[] root;
        final byte[] txid;
        final int vout;
        final byte[] blockHash;
        final int blockHeight;

        Locators(byte[] root, byte[] txid, int vout, byte[] blockHash, int blockHeight) {
            this.root = root;
            this.txid = txid;
            this.vout = vout;
            this.blockHash = blockHash;
            this.blockHeight = blockHeight;
        }
    }

    static boolean validate(byte[] bytes, byte[] waypoint) {
        return decode(bytes, waypoint) != null;
    }

    static Locators locators(byte[] bytes) {
        Decoded decoded = decode(bytes, null);
        if (decoded == null) {
            return null;
        }
        return new Locators(decoded.root, decoded.txid, decoded.vout, decoded.blockHash, decoded.blockHeight);
    }

    static byte[] encodeSingleLeaf(byte[] waypoint) {
        return encode(waypoint, 0, 1, new byte[0][], leafNode(waypoint), filled(32, (byte) 0x44), 2,
            filled(32, (byte) 0x55), 123_456);
    }

    /** Fixture for the three-leaf Merkle known answer. Not a produce path. */
    static byte[] encodeSortedTree(byte[][] leaves, byte[] leaf) {
        byte[][][] levels = new byte[MAX_SIBLINGS][][];
        levels[0] = new byte[leaves.length][];
        for (int i = 0; i < leaves.length; i++) {
            levels[0][i] = leafNode(leaves[i]);
        }
        int depth = 0;
        while (levels[depth].length > 1) {
            byte[][] current = levels[depth];
            int nextLength = (current.length + 1) / 2;
            byte[][] next = new byte[nextLength][];
            for (int i = 0; i < nextLength; i++) {
                byte[] left = current[i * 2];
                byte[] right = i * 2 + 1 < current.length ? current[i * 2 + 1] : left;
                next[i] = parentNode(left, right);
            }
            depth++;
            levels[depth] = next;
        }
        int leafIndex = -1;
        for (int i = 0; i < leaves.length; i++) {
            if (Arrays.equals(leaves[i], leaf)) {
                leafIndex = i;
                break;
            }
        }
        byte[][] siblings = new byte[depth][];
        int index = leafIndex;
        int width = leaves.length;
        for (int level = 0; level < depth; level++) {
            int siblingIndex;
            if (width % 2 == 1 && index == width - 1) {
                siblingIndex = index;
            } else {
                siblingIndex = index ^ 1;
            }
            siblings[level] = levels[level][siblingIndex];
            index /= 2;
            width = (width + 1) / 2;
        }
        return encode(leaf, leafIndex, leaves.length, siblings, levels[depth][0], filled(32, (byte) 0x44), 2,
            filled(32, (byte) 0x55), 123_456);
    }

    static byte[] leafNode(byte[] leaf) {
        byte[] preimage = new byte[33];
        preimage[0] = LEAF_DOMAIN;
        System.arraycopy(leaf, 0, preimage, 1, 32);
        return Sha256.hash(preimage);
    }

    static byte[] parentNode(byte[] left, byte[] right) {
        byte[] preimage = new byte[65];
        preimage[0] = PARENT_DOMAIN;
        System.arraycopy(left, 0, preimage, 1, 32);
        System.arraycopy(right, 0, preimage, 33, 32);
        return Sha256.hash(preimage);
    }

    private static byte[] encode(
        byte[] leaf,
        int leafIndex,
        int treeSize,
        byte[][] siblings,
        byte[] root,
        byte[] txid,
        int vout,
        byte[] blockHash,
        int blockHeight
    ) {
        byte[] bytes = new byte[FIXED_BODY_BYTES + siblings.length * 32];
        int offset = 0;
        bytes[offset++] = (byte) PROOF_BODY_VERSION;
        System.arraycopy(leaf, 0, bytes, offset, 32);
        offset += 32;
        putBe32(bytes, offset, leafIndex);
        offset += 4;
        putBe32(bytes, offset, treeSize);
        offset += 4;
        bytes[offset++] = (byte) siblings.length;
        for (int i = 0; i < siblings.length; i++) {
            System.arraycopy(siblings[i], 0, bytes, offset, 32);
            offset += 32;
        }
        System.arraycopy(root, 0, bytes, offset, 32);
        offset += 32;
        System.arraycopy(txid, 0, bytes, offset, 32);
        offset += 32;
        putBe32(bytes, offset, vout);
        offset += 4;
        System.arraycopy(blockHash, 0, bytes, offset, 32);
        offset += 32;
        putBe32(bytes, offset, blockHeight);
        return bytes;
    }

    private static Decoded decode(byte[] bytes, byte[] requiredLeaf) {
        if (bytes.length < FIXED_BODY_BYTES || (bytes[0] & 0xff) != PROOF_BODY_VERSION) {
            return null;
        }
        int siblingCount = bytes[41] & 0xff;
        if (siblingCount > MAX_SIBLINGS) {
            return null;
        }
        int expected = FIXED_BODY_BYTES + siblingCount * 32;
        if (bytes.length != expected) {
            return null;
        }
        byte[] leaf = Arrays.copyOfRange(bytes, 1, 33);
        int leafIndex = be32(bytes, 33);
        int treeSize = be32(bytes, 37);
        byte[][] siblings = new byte[siblingCount][];
        int offset = 42;
        for (int i = 0; i < siblingCount; i++) {
            siblings[i] = Arrays.copyOfRange(bytes, offset, offset + 32);
            offset += 32;
        }
        byte[] root = Arrays.copyOfRange(bytes, offset, offset + 32);
        offset += 32;
        byte[] txid = Arrays.copyOfRange(bytes, offset, offset + 32);
        offset += 32;
        int vout = be32(bytes, offset);
        offset += 4;
        byte[] blockHash = Arrays.copyOfRange(bytes, offset, offset + 32);
        offset += 32;
        int blockHeight = be32(bytes, offset);
        if (requiredLeaf != null && !Arrays.equals(leaf, requiredLeaf)) {
            return null;
        }
        if (!merkle(leaf, leafIndex, treeSize, siblings, root)) {
            return null;
        }
        return new Decoded(root, txid, vout, blockHash, blockHeight);
    }

    private static boolean merkle(byte[] leaf, int leafIndex, int treeSize, byte[][] siblings, byte[] root) {
        if (treeSize == 0 || Integer.compareUnsigned(leafIndex, treeSize) >= 0) {
            return false;
        }
        int expected = canonicalSiblingCount(treeSize);
        if (expected < 0 || siblings.length != expected) {
            return false;
        }
        byte[] node = leafNode(leaf);
        int index = leafIndex;
        int width = treeSize;
        for (int i = 0; i < siblings.length; i++) {
            byte[] sibling = siblings[i];
            if ((width & 1) == 1 && index == width - 1) {
                if (!Arrays.equals(sibling, node)) {
                    return false;
                }
                node = parentNode(node, node);
            } else if ((index & 1) == 0) {
                node = parentNode(node, sibling);
            } else {
                node = parentNode(sibling, node);
            }
            index = Integer.divideUnsigned(index, 2);
            width = Integer.divideUnsigned(width, 2) + (width & 1);
        }
        return Arrays.equals(node, root);
    }

    private static int canonicalSiblingCount(int width) {
        if (width == 0) {
            return -1;
        }
        int count = 0;
        int current = width;
        while (current > 1) {
            current = current / 2 + current % 2;
            count++;
        }
        return count;
    }

    private static int be32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
            | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8)
            | (bytes[offset + 3] & 0xff);
    }

    private static void putBe32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static byte[] filled(int length, byte value) {
        byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }

    private static final class Decoded {
        final byte[] root;
        final byte[] txid;
        final int vout;
        final byte[] blockHash;
        final int blockHeight;

        Decoded(byte[] root, byte[] txid, int vout, byte[] blockHash, int blockHeight) {
            this.root = root;
            this.txid = txid;
            this.vout = vout;
            this.blockHash = blockHash;
            this.blockHeight = blockHeight;
        }
    }

    private PathBody() {
    }
}
