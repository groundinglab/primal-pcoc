package net.primal.android.pcoc.hike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Section 14 in order, then section 15 is {@link #admit}. A mismatch does not skip a later record.
 * The pool of {@link HikeConst#REWALK_THREADS} is built inside the leg check, only after a successful draw.
 */
public final class HikeVerify {

    public enum LegRate {
        NOT_READ
    }

    public enum SixKind {
        NOT_OBTAINED,
        COULD_NOT_DRAW,
        DRAW_NOT_OBTAINED,
        REWALKED
    }

    public static final class Checkpoints {
        public final boolean[] beaconMatchesIndex;
        public final boolean[][] digestMatches;
        public final boolean mismatch;

        Checkpoints(boolean[] beaconMatchesIndex, boolean[][] digestMatches, boolean mismatch) {
            this.beaconMatchesIndex = beaconMatchesIndex;
            this.digestMatches = digestMatches;
            this.mismatch = mismatch;
        }
    }

    public static final class Waypoints {
        public final boolean openingWaypointIsZero;
        public final boolean[] successorDiffers;
        public final long absentPaths;
        public final boolean presentPathDoesNotMatchPlant;

        Waypoints(
            boolean openingWaypointIsZero,
            boolean[] successorDiffers,
            long absentPaths,
            boolean presentPathDoesNotMatchPlant
        ) {
            this.openingWaypointIsZero = openingWaypointIsZero;
            this.successorDiffers = successorDiffers;
            this.absentPaths = absentPaths;
            this.presentPathDoesNotMatchPlant = presentPathDoesNotMatchPlant;
        }
    }

    public enum GapKind {
        NOT_READ,
        NOT_DEFINED,
        MILLIS
    }

    public enum RateKind {
        NOT_READ,
        NOT_DEFINED,
        THOUSANDTHS
    }

    public static final class StoredStage {
        public final long index;
        public final long beaconHeight;
        public final Long minerTimeUnixMs;
        public final Long nextStoredBeacon;
        public final GapKind gap;
        public final long gapMillis;
        public final long legCount;
        public final RateKind rate;
        public final long thousandths;

        StoredStage(
            long index,
            long beaconHeight,
            Long minerTimeUnixMs,
            Long nextStoredBeacon,
            GapKind gap,
            long gapMillis,
            long legCount,
            RateKind rate,
            long thousandths
        ) {
            this.index = index;
            this.beaconHeight = beaconHeight;
            this.minerTimeUnixMs = minerTimeUnixMs;
            this.nextStoredBeacon = nextStoredBeacon;
            this.gap = gap;
            this.gapMillis = gapMillis;
            this.legCount = legCount;
            this.rate = rate;
            this.thousandths = thousandths;
        }
    }

    public static final class SixLegs {
        public final SixKind kind;
        public final long[] indices;
        public final boolean[] matches;

        SixLegs(SixKind kind, long[] indices, boolean[] matches) {
            this.kind = kind;
            this.indices = indices;
            this.matches = matches;
        }
    }

    public static final class Measurements {
        public final boolean shape;
        /** Null when the measurement was not obtained. */
        public final Checkpoints checkpoints;
        public final Waypoints waypoints;
        public final LegRate legRate;
        /** Null when the count was not obtained. */
        public final Long pathsIncluded;
        public final SixLegs sixLegs;
        /** Null when the stored-beacon table was not read. Not section 14.4 and not section 15. */
        public final StoredStage[] stages;

        Measurements(
            boolean shape,
            Checkpoints checkpoints,
            Waypoints waypoints,
            LegRate legRate,
            Long pathsIncluded,
            SixLegs sixLegs,
            StoredStage[] stages
        ) {
            this.shape = shape;
            this.checkpoints = checkpoints;
            this.waypoints = waypoints;
            this.legRate = legRate;
            this.pathsIncluded = pathsIncluded;
            this.sixLegs = sixLegs;
            this.stages = stages;
        }
    }

    public static Measurements verify(byte[] bytes, IndexSource index, Draw.Entropy entropy)
        throws HikeUnfinished, IndexUnreachable {
        return verify(bytes, index, entropy, null);
    }

    public static Measurements verify(
        byte[] bytes,
        IndexSource index,
        Draw.Entropy entropy,
        VerifyProgress progress
    ) throws HikeUnfinished, IndexUnreachable {
        HikeObject.Measured measured = HikeObject.readMeasured(bytes);
        boolean shape = measured != null && measured.shape;
        HikeObject.Hike hike = measured == null ? null : measured.hike;
        TerrainRoom terrain = null;
        try {
            Checkpoints checkpoints = null;
            Long[] stageTimes = null;
            if (hike != null) {
                note(progress, Note.INDEX_READS);
                long indexStarted = System.nanoTime();
                BeaconPass beacons;
                try {
                    beacons = beaconRecords(hike, index);
                } finally {
                    StageLog.debug("index reads", indexStarted);
                }
                boolean digests = anyCheckpoints(hike);
                boolean waypointInputs = hike.stages.length >= HikeConst.STAGES;
                if (digests || waypointInputs) {
                    terrain = terrainOf(hike, progress);
                }
                note(progress, Note.FILE_CHECKS);
                long checksStarted = System.nanoTime();
                boolean[][] digestMatches;
                try {
                    digestMatches = digestRecords(hike, terrain);
                } finally {
                    StageLog.debug("file checks", checksStarted);
                }
                checkpoints = new Checkpoints(
                    beacons.matches,
                    digestMatches,
                    mismatch(beacons.matches, digestMatches)
                );
                stageTimes = beacons.times;
            }
            Waypoints waypoints = null;
            if (hike != null && hike.stages.length >= HikeConst.STAGES) {
                if (terrain == null) {
                    terrain = terrainOf(hike, progress);
                }
                note(progress, Note.FILE_CHECKS);
                waypoints = waypointRecords(hike, terrain, index, progress);
            }
            StoredStage[] stages = hike == null ? null : storedStages(hike, stageTimes);
            Long pathsIncluded = hike == null ? null : Long.valueOf(countPaths(hike));
            SixLegs sixLegs;
            if (hike == null) {
                sixLegs = new SixLegs(SixKind.NOT_OBTAINED, null, null);
            } else {
                Long total = totalLegs(hike);
                if (total == null) {
                    sixLegs = new SixLegs(SixKind.NOT_OBTAINED, null, null);
                } else if (Long.compareUnsigned(total.longValue(), HikeConst.REWALK_COUNT) < 0) {
                    sixLegs = new SixLegs(SixKind.COULD_NOT_DRAW, null, null);
                } else {
                    if (terrain == null) {
                        terrain = terrainOf(hike, progress);
                    }
                    sixLegs = measureSix(hike, terrain, entropy, progress);
                }
            }
            return new Measurements(shape, checkpoints, waypoints, LegRate.NOT_READ, pathsIncluded, sixLegs, stages);
        } finally {
            if (terrain != null) {
                terrain.release();
            }
        }
    }

    /** Section 15. An unread leg rate does not fail. An absent path does not fail. */
    public static boolean admit(Measurements measurements) {
        if (!measurements.shape) {
            return false;
        }
        if (measurements.checkpoints != null && measurements.checkpoints.mismatch) {
            return false;
        }
        if (measurements.waypoints != null) {
            if (measurements.waypoints.presentPathDoesNotMatchPlant) {
                return false;
            }
            for (int i = 0; i < measurements.waypoints.successorDiffers.length; i++) {
                if (measurements.waypoints.successorDiffers[i]) {
                    return false;
                }
            }
        }
        if (measurements.sixLegs.kind == SixKind.REWALKED) {
            for (int i = 0; i < measurements.sixLegs.matches.length; i++) {
                if (!measurements.sixLegs.matches[i]) {
                    return false;
                }
            }
        } else if (measurements.sixLegs.kind == SixKind.COULD_NOT_DRAW
            || measurements.sixLegs.kind == SixKind.DRAW_NOT_OBTAINED) {
            return false;
        }
        if (measurements.pathsIncluded != null
            && Long.compareUnsigned(measurements.pathsIncluded.longValue(), 0L) < 0) {
            return false;
        }
        return true;
    }

    static ThreadPoolExecutor openPool() {
        return (ThreadPoolExecutor) Executors.newFixedThreadPool(HikeConst.REWALK_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "pcoc-leg");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
    }

    static SixLegs measureSix(HikeObject.Hike hike, TerrainRoom terrain, Draw.Entropy entropy) throws HikeUnfinished {
        return measureSix(hike, terrain, entropy, null);
    }

    static SixLegs measureSix(
        HikeObject.Hike hike,
        TerrainRoom terrain,
        Draw.Entropy entropy,
        VerifyProgress progress
    ) throws HikeUnfinished {
        Long total = totalLegs(hike);
        if (total == null) {
            return new SixLegs(SixKind.NOT_OBTAINED, null, null);
        }
        if (Long.compareUnsigned(total.longValue(), HikeConst.REWALK_COUNT) < 0) {
            return new SixLegs(SixKind.COULD_NOT_DRAW, null, null);
        }
        long[] indices;
        try {
            indices = Draw.distinct(total.longValue(), HikeConst.REWALK_COUNT, entropy);
        } catch (Draw.Unavailable unavailable) {
            return new SixLegs(SixKind.DRAW_NOT_OBTAINED, null, null);
        }
        boolean[] matches = runLegs(hike, terrain, indices, progress);
        return new SixLegs(SixKind.REWALKED, indices, matches);
    }

    private static boolean[] runLegs(
        HikeObject.Hike hike,
        TerrainRoom terrain,
        long[] indices,
        VerifyProgress progress
    ) throws HikeUnfinished {
        ThreadPoolExecutor pool = openPool();
        if (pool.prestartAllCoreThreads() != HikeConst.REWALK_THREADS) {
            stopPool(pool);
            throw new HikeUnfinished();
        }
        long started = System.nanoTime();
        try {
            if (progress != null) {
                progress.leg(0, indices.length);
            }
            stopIfCancelled();
            List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>(indices.length);
            for (int i = 0; i < indices.length; i++) {
                stopIfCancelled();
                long index = indices[i];
                futures.add(pool.submit(() -> oneLeg(hike, terrain, index)));
            }
            boolean[] matches = new boolean[indices.length];
            for (int i = 0; i < futures.size(); i++) {
                stopIfCancelled();
                matches[i] = futures.get(i).get().booleanValue();
                if (progress != null) {
                    progress.leg(i + 1, indices.length);
                }
            }
            return matches;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HikeUnfinished();
        } catch (CancellationException cancelled) {
            Thread.currentThread().interrupt();
            throw new HikeUnfinished();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof OutOfMemoryError) {
                throw (OutOfMemoryError) failed.getCause();
            }
            throw new HikeUnfinished();
        } finally {
            stopPool(pool);
            StageLog.debug("legs", started);
        }
    }

    private static void stopPool(ThreadPoolExecutor pool) {
        pool.shutdownNow();
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!pool.isTerminated() && System.nanoTime() < deadline) {
            try {
                pool.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
                pool.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean oneLeg(HikeObject.Hike hike, TerrainRoom terrain, long index) throws HikeUnfinished {
        int[] place = locate(hike, index);
        HikeObject.Stage stage = hike.stages[place[0]];
        int leg = place[1];
        byte[] previous = leg == 0 ? HikeConst.ZERO_32 : stage.checkpoints[leg - 1];
        long stored = Le.u64(stage.legResults[leg], 0);
        return Leg.legResult(terrain, previous) == stored;
    }

    private static int[] locate(HikeObject.Hike hike, long index) {
        long start = 0L;
        for (int stage = 0; stage < hike.stages.length; stage++) {
            long length = hike.stages[stage].legCount;
            long end = start + length;
            if (Long.compareUnsigned(index, end) < 0) {
                return new int[] {stage, (int) (index - start)};
            }
            start = end;
        }
        throw new IllegalStateException("global index is inside the hike");
    }

    private static final class BeaconPass {
        final boolean[] matches;
        final Long[] times;

        BeaconPass(boolean[] matches, Long[] times) {
            this.matches = matches;
            this.times = times;
        }
    }

    private static BeaconPass beaconRecords(HikeObject.Hike hike, IndexSource index)
        throws IndexUnreachable, HikeUnfinished {
        boolean[] matches = new boolean[hike.stages.length];
        Long[] times = new Long[hike.stages.length];
        for (int i = 0; i < hike.stages.length; i++) {
            stopIfCancelled();
            HikeObject.Stage stage = hike.stages[i];
            String body = index.read(stage.beaconHeight);
            IndexJson.Beacon beacon = body == null ? null : IndexJson.beacon(body);
            boolean heightMatches = beacon != null && beacon.height == stage.beaconHeight;
            boolean hashMatches = beacon != null && Arrays.equals(beacon.natural, stage.beacon);
            matches[i] = heightMatches && hashMatches;
            times[i] = beacon == null ? null : beacon.timestampUnixMs;
        }
        return new BeaconPass(matches, times);
    }

    private static boolean[][] digestRecords(HikeObject.Hike hike, TerrainRoom terrain) throws HikeUnfinished {
        boolean[][] out = new boolean[hike.stages.length][];
        byte[] preimage = null;
        Sha256 sha = null;
        byte[] digest = null;
        for (int stageIndex = 0; stageIndex < hike.stages.length; stageIndex++) {
            stopIfCancelled();
            HikeObject.Stage stage = hike.stages[stageIndex];
            if (stage.checkpoints.length == 0) {
                out[stageIndex] = new boolean[0];
                continue;
            }
            if (preimage == null) {
                preimage = new byte[HikeConst.CHECKPOINT_PREIMAGE_LEN];
                sha = new Sha256();
                digest = new byte[32];
            }
            boolean[] legs = new boolean[stage.checkpoints.length];
            for (int leg = 0; leg < stage.checkpoints.length; leg++) {
                stopIfCancelled();
                byte[] previous = leg == 0 ? HikeConst.ZERO_32 : stage.checkpoints[leg - 1];
                Bind.fillCheckpointPreimage(terrain, preimage, previous, stage.waypoint, stage.legResults[leg]);
                sha.reset();
                sha.update(preimage, 0, preimage.length);
                sha.digest(digest);
                legs[leg] = Arrays.equals(digest, stage.checkpoints[leg]);
            }
            out[stageIndex] = legs;
        }
        return out;
    }

    private static boolean mismatch(boolean[] beacons, boolean[][] digests) {
        for (int i = 0; i < beacons.length; i++) {
            if (!beacons[i]) {
                return true;
            }
        }
        for (int i = 0; i < digests.length; i++) {
            for (int leg = 0; leg < digests[i].length; leg++) {
                if (!digests[i][leg]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Waypoints waypointRecords(
        HikeObject.Hike hike,
        TerrainRoom terrain,
        IndexSource index,
        VerifyProgress progress
    )
        throws IndexUnreachable, HikeUnfinished {
        long checksStarted = System.nanoTime();
        boolean opening = Arrays.equals(hike.stages[0].waypoint, HikeConst.ZERO_32);
        boolean[] differs = new boolean[HikeConst.STAGES - 1];
        Sha256 sha = new Sha256();
        byte[] pair = new byte[64];
        byte[] digest = new byte[32];
        byte[] slotBytes = new byte[HikeConst.SLOT_BYTES];
        for (int stage = 0; stage < HikeConst.STAGES - 1; stage++) {
            stopIfCancelled();
            HikeObject.Stage current = hike.stages[stage];
            byte[] successor = Bind.successorWaypoint(
                terrain,
                current.beacon,
                current.waypoint,
                current.checkpoints,
                sha,
                pair,
                digest,
                slotBytes
            );
            differs[stage] = !Arrays.equals(successor, hike.stages[stage + 1].waypoint);
        }
        long absent = 0L;
        boolean presentFails = false;
        long indexStarted = 0L;
        for (int i = 0; i < hike.stages.length; i++) {
            HikeObject.Stage stage = hike.stages[i];
            if (stage.path.length == 0) {
                absent++;
                continue;
            }
            if (!PathBody.validate(stage.path, stage.waypoint)) {
                presentFails = true;
                continue;
            }
            PathBody.Locators locators = PathBody.locators(stage.path);
            if (locators == null) {
                presentFails = true;
                continue;
            }
            stopIfCancelled();
            if (indexStarted == 0L) {
                StageLog.debug("file checks", checksStarted);
                note(progress, Note.INDEX_READS);
                indexStarted = System.nanoTime();
            }
            String body = index.read(Integer.toUnsignedLong(locators.blockHeight));
            if (body == null || !IndexJson.proxyMain(body, locators)) {
                presentFails = true;
            }
        }
        if (indexStarted == 0L) {
            StageLog.debug("file checks", checksStarted);
        } else {
            StageLog.debug("index reads", indexStarted);
        }
        return new Waypoints(opening, differs, absent, presentFails);
    }

    private static StoredStage[] storedStages(HikeObject.Hike hike, Long[] times) {
        StoredStage[] rows = new StoredStage[hike.stages.length];
        for (int i = 0; i < hike.stages.length; i++) {
            HikeObject.Stage stage = hike.stages[i];
            Long miner = times != null && i < times.length ? times[i] : null;
            boolean last = i + 1 == hike.stages.length;
            Long next = null;
            GapKind gap = GapKind.NOT_DEFINED;
            long gapMillis = 0L;
            RateKind rate = RateKind.NOT_DEFINED;
            long thousandths = 0L;
            if (!last) {
                next = Long.valueOf(hike.stages[i + 1].beaconHeight);
                Long closed = i + 1 < times.length ? times[i + 1] : null;
                if (miner != null && closed != null && Long.compareUnsigned(closed.longValue(), miner.longValue()) > 0) {
                    gapMillis = closed.longValue() - miner.longValue();
                    gap = GapKind.MILLIS;
                    Long computed = rateThousandths(stage.legCount, gapMillis);
                    if (computed == null) {
                        rate = RateKind.NOT_DEFINED;
                    } else {
                        rate = RateKind.THOUSANDTHS;
                        thousandths = computed.longValue();
                    }
                } else if (miner != null && closed != null) {
                    gap = GapKind.NOT_DEFINED;
                    rate = RateKind.NOT_DEFINED;
                } else {
                    gap = GapKind.NOT_READ;
                    rate = RateKind.NOT_READ;
                }
            }
            rows[i] = new StoredStage(
                i,
                stage.beaconHeight,
                miner,
                next,
                gap,
                gapMillis,
                stage.legCount,
                rate,
                thousandths
            );
        }
        return rows;
    }

    static Long rateThousandths(long legs, long gapMillis) {
        if (gapMillis == 0L) {
            return null;
        }
        if (Long.compareUnsigned(legs, Long.divideUnsigned(-1L, 1_000_000L)) > 0) {
            return null;
        }
        long numer = legs * 1_000_000L;
        long quotient = Long.divideUnsigned(numer, gapMillis);
        long remainder = Long.remainderUnsigned(numer, gapMillis);
        if (Long.compareUnsigned(remainder, Long.divideUnsigned(-1L, 2L)) > 0) {
            return null;
        }
        long twice = remainder * 2L;
        if (Long.compareUnsigned(twice, gapMillis) >= 0) {
            if (quotient == -1L) {
                return null;
            }
            return Long.valueOf(quotient + 1L);
        }
        return Long.valueOf(quotient);
    }

    private static long countPaths(HikeObject.Hike hike) {
        long count = 0L;
        for (int i = 0; i < hike.stages.length; i++) {
            if (hike.stages[i].path.length != 0) {
                count++;
            }
        }
        return count;
    }

    private static boolean anyCheckpoints(HikeObject.Hike hike) {
        for (int i = 0; i < hike.stages.length; i++) {
            if (hike.stages[i].checkpoints.length != 0) {
                return true;
            }
        }
        return false;
    }

    private static TerrainRoom terrainOf(HikeObject.Hike hike, VerifyProgress progress) throws HikeUnfinished {
        note(progress, Note.TERRAIN);
        long started = System.nanoTime();
        try {
            return Terrain.fill(Terrain.fillPin(Terrain.productionSeed(hike.publicKey, hike.seedBeacon)));
        } finally {
            StageLog.debug("terrain", started);
        }
    }

    private static void note(VerifyProgress progress, Note note) {
        if (progress == null) {
            return;
        }
        if (note == Note.INDEX_READS) {
            progress.indexReads();
        } else if (note == Note.TERRAIN) {
            progress.terrain();
        } else {
            progress.checks();
        }
    }

    private static void stopIfCancelled() throws HikeUnfinished {
        if (Thread.currentThread().isInterrupted()) {
            throw new HikeUnfinished();
        }
    }

    private enum Note {
        INDEX_READS,
        TERRAIN,
        FILE_CHECKS
    }

    private static Long totalLegs(HikeObject.Hike hike) {
        long total = 0L;
        for (int i = 0; i < hike.stages.length; i++) {
            long next = total + hike.stages[i].legCount;
            if (Long.compareUnsigned(next, total) < 0) {
                return null;
            }
            total = next;
        }
        return Long.valueOf(total);
    }

    private HikeVerify() {
    }
}
