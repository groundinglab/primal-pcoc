package net.primal.android.pcoc.hike;

/** One section 9 body, or null when the read is not successful. */
public interface IndexSource {
    String read(long height);
}
