package net.primal.android.pcoc.hike;

import java.util.Arrays;

/** Sections 11.3, 11.4, and 12.2. */
final class Bind {

    private Bind() {
    }

    static int sampleOffset(byte[] previousCheckpoint) {
        return (int) Long.remainderUnsigned(Le.u64(previousCheckpoint, 0), HikeConst.OFFSET_MODULUS);
    }

    static void fillCheckpointPreimage(
        TerrainRoom terrain,
        byte[] preimage,
        byte[] previous,
        byte[] waypoint,
        byte[] legResultLimb
    ) {
        System.arraycopy(previous, 0, preimage, 0, 32);
        System.arraycopy(waypoint, 0, preimage, 32, 32);
        terrain.copyBytes(sampleOffset(previous), preimage, 64, HikeConst.SAMPLE_BYTES);
        System.arraycopy(legResultLimb, 0, preimage, 64 + HikeConst.SAMPLE_BYTES, 8);
    }

    static byte[] foldCheckpoints(Sha256 sha, byte[][] checkpoints, byte[] pair, byte[] digest) {
        byte[] fold = new byte[32];
        for (byte[] checkpoint : checkpoints) {
            System.arraycopy(fold, 0, pair, 0, 32);
            System.arraycopy(checkpoint, 0, pair, 32, 32);
            sha.reset();
            sha.update(pair, 0, 64);
            sha.digest(digest);
            System.arraycopy(digest, 0, fold, 0, 32);
        }
        return fold;
    }

    static byte[] successorWaypoint(
        TerrainRoom terrain,
        byte[] beacon,
        byte[] openingWaypoint,
        byte[][] checkpoints,
        Sha256 sha,
        byte[] pair,
        byte[] digest,
        byte[] slotBytes
    ) {
        byte[] fold = foldCheckpoints(sha, checkpoints, pair, digest);
        int slot = (int) Long.remainderUnsigned(Le.u64(beacon, 0), HikeConst.N_SLOTS);
        terrain.copySlot(slot, slotBytes);
        sha.reset();
        sha.update(beacon, 0, 32);
        sha.update(slotBytes, 0, HikeConst.SLOT_BYTES);
        sha.update(openingWaypoint, 0, 32);
        sha.update(fold, 0, 32);
        sha.digest(digest);
        return Arrays.copyOf(digest, 32);
    }
}
