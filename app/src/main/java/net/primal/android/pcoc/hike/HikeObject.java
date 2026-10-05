package net.primal.android.pcoc.hike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Section 8 object and the section 14.1 shape measurement. */
final class HikeObject {

    static final class Stage {
        final byte[] beacon;
        final long beaconHeight;
        final long legCount;
        final byte[] waypoint;
        final byte[] path;
        final byte[][] checkpoints;
        final byte[][] legResults;

        Stage(
            byte[] beacon,
            long beaconHeight,
            long legCount,
            byte[] waypoint,
            byte[] path,
            byte[][] checkpoints,
            byte[][] legResults
        ) {
            this.beacon = beacon;
            this.beaconHeight = beaconHeight;
            this.legCount = legCount;
            this.waypoint = waypoint;
            this.path = path;
            this.checkpoints = checkpoints;
            this.legResults = legResults;
        }
    }

    static final class Hike {
        final byte[] publicKey;
        final byte[] seedBeacon;
        final Stage[] stages;

        Hike(byte[] publicKey, byte[] seedBeacon, Stage[] stages) {
            this.publicKey = publicKey;
            this.seedBeacon = seedBeacon;
            this.stages = stages;
        }
    }

    static final class Measured {
        final Hike hike;
        final boolean shape;

        Measured(Hike hike, boolean shape) {
            this.hike = hike;
            this.shape = shape;
        }
    }

    private static final String[] TOP_KEYS = {
        "fill",
        "kind",
        "stages",
        "n_slots",
        "public_key",
        "slot_bytes",
        "seed_beacon",
        "sample_bytes",
        "steps_per_leg",
    };

    private static final String[] STAGE_KEYS = {
        "path",
        "beacon",
        "waypoint",
        "leg_count",
        "checkpoints",
        "leg_results",
        "beacon_height",
    };

    /**
     * A usable hike, or null when the bytes did not yield one.
     * A false {@code shape} still carries decoded stage values.
     */
    static Measured readMeasured(byte[] bytes) {
        try {
            return read(bytes);
        } catch (Cbor.Fail ignored) {
            return null;
        }
    }

    static byte[] encode(Hike hike) {
        return Cbor.encode(hikeValue(hike));
    }

    static byte[] encodeWide(Hike hike) {
        return Cbor.encodeWide(hikeValue(hike));
    }

    static Hike sample() {
        Stage[] stages = new Stage[HikeConst.STAGES];
        for (int stage = 0; stage < HikeConst.STAGES; stage++) {
            byte[] beacon = new byte[32];
            beacon[0] = (byte) (stage + 1);
            byte[] waypoint = stage == 0 ? new byte[32] : filled(32, (byte) stage);
            stages[stage] = new Stage(
                beacon,
                stage + 1L,
                1L,
                waypoint,
                PathBody.encodeSingleLeaf(waypoint),
                new byte[][] {filled(32, (byte) 0xAB)},
                new byte[][] {{1, 0, 0, 0, 0, 0, 0, 0}}
            );
        }
        return new Hike(filled(32, (byte) 0x11), Arrays.copyOf(stages[0].beacon, 32), stages);
    }

    private static Measured read(byte[] bytes) throws Cbor.Fail {
        Cbor.Head head = Cbor.decodeHead(bytes);
        if (!(head.value instanceof Cbor.Map)) {
            throw new Cbor.Fail("expected a map");
        }
        List<Cbor.Pair> map = ((Cbor.Map) head.value).pairs;
        boolean shape = true;
        if (head.trailing || !exactKeys(map, TOP_KEYS)) {
            shape = false;
        }
        if (!HikeConst.KIND.equals(text(map, "kind"))) {
            shape = false;
        }
        if (!HikeConst.FILL.equals(text(map, "fill"))) {
            shape = false;
        }
        if (uint(map, "slot_bytes") != HikeConst.SLOT_BYTES) {
            shape = false;
        }
        if (uint(map, "n_slots") != HikeConst.N_SLOTS) {
            shape = false;
        }
        if (uint(map, "sample_bytes") != HikeConst.SAMPLE_BYTES) {
            shape = false;
        }
        if (uint(map, "steps_per_leg") != HikeConst.STEPS_PER_LEG) {
            shape = false;
        }
        byte[] publicKey = bytesOf(map, "public_key", 32);
        byte[] seedBeacon = bytesOf(map, "seed_beacon", 32);
        List<Cbor.Value> stageItems = array(map, "stages");
        if (stageItems.size() != HikeConst.STAGES) {
            shape = false;
        }
        Stage[] stages = new Stage[stageItems.size()];
        for (int i = 0; i < stageItems.size(); i++) {
            stages[i] = parseStage(stageItems.get(i));
        }
        for (int i = 0; i < stages.length; i++) {
            if (Long.compareUnsigned(stages[i].legCount, 1L) < 0) {
                shape = false;
                break;
            }
        }
        if (stages.length == HikeConst.STAGES) {
            if (!Arrays.equals(stages[0].beacon, seedBeacon)) {
                shape = false;
            }
            if (!Arrays.equals(stages[0].waypoint, HikeConst.ZERO_32)) {
                shape = false;
            }
            for (int stage = 0; stage < HikeConst.STAGES - 1; stage++) {
                if (Long.compareUnsigned(stages[stage + 1].beaconHeight, stages[stage].beaconHeight) <= 0) {
                    shape = false;
                    break;
                }
                if (Arrays.equals(stages[stage + 1].beacon, stages[stage].beacon)) {
                    shape = false;
                    break;
                }
            }
        }
        return new Measured(new Hike(publicKey, seedBeacon, stages), shape);
    }

    private static Stage parseStage(Cbor.Value value) throws Cbor.Fail {
        if (!(value instanceof Cbor.Map)) {
            throw new Cbor.Fail("expected a map");
        }
        List<Cbor.Pair> map = ((Cbor.Map) value).pairs;
        if (!exactKeys(map, STAGE_KEYS)) {
            throw new Cbor.Fail("map field set");
        }
        byte[] beacon = bytesOf(map, "beacon", 32);
        byte[] waypoint = bytesOf(map, "waypoint", 32);
        byte[] path = byteString(map, "path");
        long legCount = uint(map, "leg_count");
        long beaconHeight = uint(map, "beacon_height");
        List<Cbor.Value> checkpointItems = array(map, "checkpoints");
        byte[][] checkpoints = new byte[checkpointItems.size()][];
        for (int i = 0; i < checkpointItems.size(); i++) {
            checkpoints[i] = fixedBytes(checkpointItems.get(i), 32, "checkpoint");
        }
        List<Cbor.Value> legItems = array(map, "leg_results");
        byte[][] legResults = new byte[legItems.size()][];
        for (int i = 0; i < legItems.size(); i++) {
            legResults[i] = fixedBytes(legItems.get(i), 8, "leg result");
        }
        if (legCount != checkpoints.length || checkpoints.length != legResults.length) {
            throw new Cbor.Fail("leg_count");
        }
        return new Stage(beacon, beaconHeight, legCount, waypoint, path, checkpoints, legResults);
    }

    private static Cbor.Value hikeValue(Hike hike) {
        List<Cbor.Pair> pairs = new ArrayList<Cbor.Pair>();
        pairs.add(new Cbor.Pair("fill", new Cbor.Text(HikeConst.FILL)));
        pairs.add(new Cbor.Pair("kind", new Cbor.Text(HikeConst.KIND)));
        List<Cbor.Value> stages = new ArrayList<Cbor.Value>();
        for (int i = 0; i < hike.stages.length; i++) {
            stages.add(stageValue(hike.stages[i]));
        }
        pairs.add(new Cbor.Pair("stages", new Cbor.Array(stages)));
        pairs.add(new Cbor.Pair("n_slots", new Cbor.Uint(HikeConst.N_SLOTS)));
        pairs.add(new Cbor.Pair("public_key", new Cbor.Bytes(hike.publicKey)));
        pairs.add(new Cbor.Pair("slot_bytes", new Cbor.Uint(HikeConst.SLOT_BYTES)));
        pairs.add(new Cbor.Pair("seed_beacon", new Cbor.Bytes(hike.seedBeacon)));
        pairs.add(new Cbor.Pair("sample_bytes", new Cbor.Uint(HikeConst.SAMPLE_BYTES)));
        pairs.add(new Cbor.Pair("steps_per_leg", new Cbor.Uint(HikeConst.STEPS_PER_LEG)));
        return new Cbor.Map(pairs);
    }

    private static Cbor.Value stageValue(Stage stage) {
        List<Cbor.Value> checkpoints = new ArrayList<Cbor.Value>();
        for (int i = 0; i < stage.checkpoints.length; i++) {
            checkpoints.add(new Cbor.Bytes(stage.checkpoints[i]));
        }
        List<Cbor.Value> legs = new ArrayList<Cbor.Value>();
        for (int i = 0; i < stage.legResults.length; i++) {
            legs.add(new Cbor.Bytes(stage.legResults[i]));
        }
        List<Cbor.Pair> pairs = new ArrayList<Cbor.Pair>();
        pairs.add(new Cbor.Pair("path", new Cbor.Bytes(stage.path)));
        pairs.add(new Cbor.Pair("beacon", new Cbor.Bytes(stage.beacon)));
        pairs.add(new Cbor.Pair("waypoint", new Cbor.Bytes(stage.waypoint)));
        pairs.add(new Cbor.Pair("leg_count", new Cbor.Uint(stage.legCount)));
        pairs.add(new Cbor.Pair("checkpoints", new Cbor.Array(checkpoints)));
        pairs.add(new Cbor.Pair("leg_results", new Cbor.Array(legs)));
        pairs.add(new Cbor.Pair("beacon_height", new Cbor.Uint(stage.beaconHeight)));
        return new Cbor.Map(pairs);
    }

    private static boolean exactKeys(List<Cbor.Pair> map, String[] expected) {
        if (map.size() != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (find(map, expected[i]) == null) {
                return false;
            }
        }
        return true;
    }

    private static Cbor.Value find(List<Cbor.Pair> map, String key) {
        for (int i = 0; i < map.size(); i++) {
            if (key.equals(map.get(i).key)) {
                return map.get(i).value;
            }
        }
        return null;
    }

    private static String text(List<Cbor.Pair> map, String key) throws Cbor.Fail {
        Cbor.Value value = required(map, key);
        if (!(value instanceof Cbor.Text)) {
            throw new Cbor.Fail("expected text");
        }
        return ((Cbor.Text) value).value;
    }

    private static long uint(List<Cbor.Pair> map, String key) throws Cbor.Fail {
        Cbor.Value value = required(map, key);
        if (!(value instanceof Cbor.Uint)) {
            throw new Cbor.Fail("expected unsigned");
        }
        return ((Cbor.Uint) value).value;
    }

    private static byte[] byteString(List<Cbor.Pair> map, String key) throws Cbor.Fail {
        Cbor.Value value = required(map, key);
        if (!(value instanceof Cbor.Bytes)) {
            throw new Cbor.Fail("expected bytes");
        }
        return ((Cbor.Bytes) value).value;
    }

    private static byte[] bytesOf(List<Cbor.Pair> map, String key, int length) throws Cbor.Fail {
        byte[] bytes = byteString(map, key);
        if (bytes.length != length) {
            throw new Cbor.Fail("byte length");
        }
        return bytes;
    }

    private static List<Cbor.Value> array(List<Cbor.Pair> map, String key) throws Cbor.Fail {
        Cbor.Value value = required(map, key);
        if (!(value instanceof Cbor.Array)) {
            throw new Cbor.Fail("expected array");
        }
        return ((Cbor.Array) value).items;
    }

    private static Cbor.Value required(List<Cbor.Pair> map, String key) throws Cbor.Fail {
        Cbor.Value value = find(map, key);
        if (value == null) {
            throw new Cbor.Fail("missing field");
        }
        return value;
    }

    private static byte[] fixedBytes(Cbor.Value value, int length, String reason) throws Cbor.Fail {
        if (!(value instanceof Cbor.Bytes) || ((Cbor.Bytes) value).value.length != length) {
            throw new Cbor.Fail(reason);
        }
        return ((Cbor.Bytes) value).value;
    }

    private static byte[] filled(int length, byte value) {
        byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }

    private HikeObject() {
    }
}
