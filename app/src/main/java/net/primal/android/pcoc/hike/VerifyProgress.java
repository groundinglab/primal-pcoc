package net.primal.android.pcoc.hike;

/** Door progress. It does not change a finished section 14 or section 15 result. */
public interface VerifyProgress {
    void indexReads();

    void terrain();

    void checks();

    void leg(int done, int total);
}
