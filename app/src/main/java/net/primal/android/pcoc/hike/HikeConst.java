package net.primal.android.pcoc.hike;

/**
 * Satoshi Hike v1.35 section 6. {@code STAGES} is 18. There is no per-stage table.
 */
public final class HikeConst {

    public static final int SLOT_BYTES = 16384;
    public static final int WORDS_PER_SLOT = 2048;
    public static final int TERRAIN_BYTES = 268_435_456;
    public static final int N_SLOTS = 16384;
    public static final int SAMPLE_BYTES = 1_048_576;
    public static final long OFFSET_MODULUS = 267_386_881L;
    public static final int STEPS_PER_LEG = 1_000_000;
    public static final int STAGES = 18;
    public static final int HASH_BYTES = 32;
    public static final int CHECKPOINT_PREIMAGE_LEN = 32 + 32 + SAMPLE_BYTES + 8;
    public static final int WAYPOINT_PREIMAGE_LEN = 32 + SLOT_BYTES + 32 + 32;

    public static final byte[] SEAL_DOMAIN = {
        0x70, 0x63, 0x6f, 0x63, 0x2d, 0x77, 0x61, 0x6c, 0x6b, 0x2d, 0x76, 0x31,
    };
    public static final byte[] SEED_DOMAIN = {
        0x50, 0x53, 0x45, 0x45, 0x44, 0x36, 0x34, 0x00,
    };

    public static final String KIND = "satoshi-hike-v1.35-object";
    public static final String FILL = "dag2";

    public static final long MIX_ADD = 0x9E3779B97F4A7C15L;
    public static final long MIX_MUL1 = 0xBF58476D1CE4E5B9L;
    public static final long MIX_MUL2 = 0x94D049BB133111EBL;
    public static final long WORD_XOR = 0xC0FFEEL;
    public static final long PARENT_MUL = 0xD1B54A32D192ED03L;
    public static final long SLOT_MUL = 0xA24BAED4963EE407L;

    /** Section 14.6 draw count. Not the pool size. */
    public static final int REWALK_COUNT = 6;
    /** Pool size. Not the draw count and not the host CPU count. */
    public static final int REWALK_THREADS = 6;

    public static final byte[] ZERO_32 = new byte[32];

    private HikeConst() {
    }
}
