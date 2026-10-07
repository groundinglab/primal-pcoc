package net.primal.android.pcoc.hike;

/**
 * The Verify door. A decoded object yields a measurement record.
 * Bytes that do not decode are unread. The record does not carry a section 15 verdict.
 */
public final class VerifyAny {

    /** Live profile, recorded under its measurement name. This phone does not offer another profile. */
    public static final String LIVE_PROFILE = "production";

    public static final String MATCH = "match";
    public static final String MISMATCH = "mismatch";
    public static final String NOT_READ = "not read";
    public static final String NOT_DEFINED = "not defined";

    public static final class Record {
        public final String profile;
        public final String shape;
        public final String checkpoints;
        public final String waypoints;
        public final String legRate;
        public final String sixLeg;

        public Record(
            String profile,
            String shape,
            String checkpoints,
            String waypoints,
            String legRate,
            String sixLeg
        ) {
            this.profile = profile;
            this.shape = shape;
            this.checkpoints = checkpoints;
            this.waypoints = waypoints;
            this.legRate = legRate;
            this.sixLeg = sixLeg;
        }
    }

    /**
     * One finished door call. {@link #record} is set when a measurement record was written.
     * {@link #unread} is set when the bytes did not decode. Both clear means the run did not finish.
     */
    public static final class Finish {
        public final Record record;
        public final boolean unread;

        private Finish(Record record, boolean unread) {
            this.record = record;
            this.unread = unread;
        }

        static Finish recorded(Record record) {
            return new Finish(record, false);
        }

        static Finish unread() {
            return new Finish(null, true);
        }

        static Finish unfinished() {
            return new Finish(null, false);
        }
    }

    public static Finish finish(byte[] object, IndexSource index, Draw.Entropy entropy) throws IndexUnreachable {
        return finish(object, index, entropy, null);
    }

    public static Finish finish(
        byte[] object,
        IndexSource index,
        Draw.Entropy entropy,
        VerifyProgress progress
    ) throws IndexUnreachable {
        try {
            HikeVerify.Measurements measurements = HikeVerify.verify(object, index, entropy, progress);
            if (!measurements.decoded) {
                return Finish.unread();
            }
            return Finish.recorded(record(measurements));
        } catch (HikeUnfinished unfinished) {
            return Finish.unfinished();
        } catch (OutOfMemoryError allocation) {
            return Finish.unfinished();
        }
    }

    public static Finish finish(byte[] object, IndexSource index) throws IndexUnreachable {
        return finish(object, index, new OsEntropy(), null);
    }

    /**
     * Summary words for one section 14 measurement.
     * Shape, checkpoints, waypoints, and six leg are match, mismatch, not read, or not defined.
     * Leg rate is not read. This does not call {@link HikeVerify#admit}.
     */
    public static Record record(HikeVerify.Measurements measurements) {
        return new Record(
            LIVE_PROFILE,
            measurements.shape ? MATCH : MISMATCH,
            checkpoints(measurements.checkpoints),
            waypoints(measurements.waypoints),
            legRate(measurements.legRate),
            sixLeg(measurements.sixLegs)
        );
    }

    private static String checkpoints(HikeVerify.Checkpoints checkpoints) {
        if (checkpoints == null) {
            return NOT_READ;
        }
        return checkpoints.mismatch ? MISMATCH : MATCH;
    }

    /** Opening waypoint, successor links, and a present path. Not a section 15 check. */
    private static String waypoints(HikeVerify.Waypoints waypoints) {
        if (waypoints == null) {
            return NOT_READ;
        }
        if (!waypoints.openingWaypointIsZero || waypoints.presentPathDoesNotMatchPlant) {
            return MISMATCH;
        }
        if (waypoints.successorDiffers != null) {
            for (int i = 0; i < waypoints.successorDiffers.length; i++) {
                if (waypoints.successorDiffers[i]) {
                    return MISMATCH;
                }
            }
        }
        return MATCH;
    }

    /** Section 14.4 does not read a leg rate. The row stays not read. */
    private static String legRate(HikeVerify.LegRate legRate) {
        switch (legRate) {
            case NOT_READ:
                return NOT_READ;
            default:
                return NOT_READ;
        }
    }

    private static String sixLeg(HikeVerify.SixLegs six) {
        if (six == null) {
            return NOT_READ;
        }
        switch (six.kind) {
            case NOT_OBTAINED:
            case DRAW_NOT_OBTAINED:
                return NOT_READ;
            case COULD_NOT_DRAW:
                return NOT_DEFINED;
            case REWALKED:
                if (six.matches == null) {
                    return NOT_READ;
                }
                for (int i = 0; i < six.matches.length; i++) {
                    if (!six.matches[i]) {
                        return MISMATCH;
                    }
                }
                return MATCH;
            default:
                return NOT_READ;
        }
    }

    private VerifyAny() {
    }
}
