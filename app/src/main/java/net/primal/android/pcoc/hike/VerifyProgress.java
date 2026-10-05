package net.primal.android.pcoc.hike;

/** Door progress. It does not change a finished section 14 or section 15 result. */
public interface VerifyProgress {
    void indexReads();

    void terrain();

    void checks();

    void leg(int done, int total);

    /** Checkpoint preimage digests. {@code done} is how many have been hashed. */
    default void checkpoints(int done, int total) {
        checks();
    }

    /** Successor waypoints. {@code total} is the 17 links between 18 stages. */
    default void waypoints(int done, int total) {
        checks();
    }

    /** Present path bodies, before an index read for those paths. */
    default void paths(int done, int total) {
        checks();
    }
}
