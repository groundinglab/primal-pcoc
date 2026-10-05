package net.primal.android.pcoc.hike;

import java.security.SecureRandom;

/** Operating-system CSPRNG. A fresh draw for each verification. */
public final class OsEntropy implements Draw.Entropy {

    private final SecureRandom random = new SecureRandom();

    @Override
    public boolean fill(byte[] dest) {
        random.nextBytes(dest);
        return true;
    }
}
