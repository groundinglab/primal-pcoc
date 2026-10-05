package net.primal.android.pcoc.hike;

/**
 * One section 9 body, or null when the document is not a successful read.
 * {@link IndexUnreachable} is a fetch failure, not a mismatch.
 */
public interface IndexSource {
    String read(long height) throws IndexUnreachable;
}
