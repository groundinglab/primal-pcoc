package net.primal.android.pcoc.hike

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Arrays
import org.junit.Test

class HikeVerifyTest {

    @Test
    fun `constants match section 6`() {
        HikeConst.TERRAIN_BYTES shouldBe 268_435_456
        HikeConst.SAMPLE_BYTES shouldBe 1_048_576
        HikeConst.STEPS_PER_LEG shouldBe 1_000_000
        HikeConst.STAGES shouldBe 18
        HikeConst.OFFSET_MODULUS shouldBe (HikeConst.TERRAIN_BYTES - HikeConst.SAMPLE_BYTES + 1).toLong()
        HikeConst.CHECKPOINT_PREIMAGE_LEN shouldBe 1_048_648
        HikeConst.REWALK_COUNT shouldBe 6
        HikeConst.REWALK_THREADS shouldBe 6
        HikeConst.REWALK_COUNT shouldBe HikeConst.REWALK_THREADS
    }

    @Test
    fun `sha256 abc and the zero seal match section 7_4`() {
        val digest = Sha256.hash("abc".toByteArray(Charsets.US_ASCII))
        hex(digest) shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        val sha = Sha256()
        val preimage = ByteArray(20)
        HikeConst.SEAL_DOMAIN.copyInto(preimage)
        val out = ByteArray(32)
        Leg.seal(sha, preimage, out, 0L) shouldBe 0x76042423afbd1648L
        Leg.seal(sha, preimage, out, 0L) shouldBe 0x76042423afbd1648L
    }

    @Test
    fun `fill pin and parent indices match section 10`() {
        val seed = ByteArray(64)
        Arrays.fill(seed, 0, 32, 0x11)
        Arrays.fill(seed, 32, 64, 0x22)
        Terrain.fillPin(seed) shouldBe 0x7df0aff879fb1171L
        Terrain.bitrevParent(1) shouldBe 0
        Terrain.bitrevParent(5) shouldBe 2
        Terrain.mulParent(5) shouldBe 4
        Terrain.bitrevParent(16383) shouldBe 8191
        Terrain.mulParent(16383) shouldBe 9976
    }

    @Test
    fun `draw uses an unsigned compare`() {
        val one = Script(le(1L))
        Draw.one(6L, one, ByteArray(8)) shouldBe 1L
        one.pos shouldBe 8

        val power = Script(le(-1L))
        Draw.one(8L, power, ByteArray(8)) shouldBe 7L

        val rejected = Script(le(-1L) + le(0L))
        Draw.one(6L, rejected, ByteArray(8)) shouldBe 0L
        rejected.pos shouldBe 16

        val distinct = Script(le(0L) + le(0L) + le(1L))
        Draw.distinct(6L, 2, distinct).toList() shouldBe listOf(0L, 1L)
    }

    @Test
    fun `fewer than six legs does not draw and a failed source does not open the pool`() {
        val boom = Draw.Entropy { _ -> error("drew") }
        HikeVerify.measureSix(hikeWithLegs(5), ByteArray(0), boom).kind shouldBe HikeVerify.SixKind.COULD_NOT_DRAW

        val empty = Draw.Entropy { _ -> false }
        HikeVerify.measureSix(hikeWithLegs(6), ByteArray(0), empty).kind shouldBe
            HikeVerify.SixKind.DRAW_NOT_OBTAINED
    }

    @Test
    fun `the leg pool is six threads`() {
        val pool = HikeVerify.openPool()
        pool.corePoolSize shouldBe 6
        pool.maximumPoolSize shouldBe 6
        pool.prestartAllCoreThreads() shouldBe 6
        pool.shutdownNow()
    }

    @Test
    fun `section 9 rejects a bad index body and keeps a u64 height`() {
        val hash = (0 until 32).joinToString("") { "%02x".format(it) }
        val body = """{"extra":1,"header":{"hash":"$hash","height":42,"bits":1}}"""
        val beacon = IndexJson.beacon(body)
        beacon shouldNotBe null
        beacon!!.height shouldBe 42L
        beacon.natural[0] shouldBe 31
        beacon.natural[31] shouldBe 0

        val upper = """{"header":{"hash":"${hash.uppercase()}","height":18446744073709551615}}"""
        IndexJson.beacon(upper)!!.height shouldBe -1L

        IndexJson.beacon("""{"header":{"hash":"$hash","height":1.5}}""") shouldBe null
        IndexJson.beacon("""{"header":{"hash":"0x$hash","height":1}}""") shouldBe null
        IndexJson.beacon("""{"header":{"hash":"${hash.take(62)}","height":1}}""") shouldBe null
        IndexJson.beacon("""{"hash":"aa","height":1}""") shouldBe null
        IndexJson.beacon("""{"header":{"hash":"$hash","height":-1}}""") shouldBe null
        IndexJson.beacon("""{"header":{"hash":"$hash","height":42.0}}""") shouldBe null
        IndexJson.beacon("""{"header":{"hash":"$hash","height":1e2}}""") shouldBe null
        IndexJson.beacon("\uFEFF{\"header\":{\"hash\":\"$hash\",\"height\":1}}") shouldBe null
        IndexJson.beacon("""{"header":{"hash":"$hash","height":1}} {"header":{"hash":"$hash","height":2}}""") shouldBe
            null
        IndexJson.beacon(
            """{"header":{"hash":"$hash","height":1},"header":{"hash":"$hash","height":2}}""",
        ) shouldBe null
        IndexJson.beacon("""{"header":{"hash":"$hash","hash":"$hash","height":1}}""") shouldBe null
        val stamped = """{"network":"synthetic","header":{"hash":"$hash","height":7,"timestamp_unix_ms":"no"}}"""
        IndexJson.beacon(stamped)!!.timestampUnixMs shouldBe null
        IndexJson.beacon(stamped)!!.height shouldBe 7L
    }

    @Test
    fun `mainnet proxy matches one plant and rejects synthetic`() {
        val locators = locators()
        val document = proxyDocument(locators)
        IndexJson.proxyMain(document, locators) shouldBe true
        IndexJson.proxyMain(document.replace(",\"tree_size\":99", ""), locators) shouldBe true
        IndexJson.proxyMain(document.replace("\"network\":\"main\"", "\"network\":\"synthetic\""), locators) shouldBe
            false
        IndexJson.proxyMain("""{"network":"main","plant":null}""", locators) shouldBe false
        IndexJson.proxyMain("""{"network":"main"}""", locators) shouldBe false
        IndexJson.proxyMain("", locators) shouldBe false
        for (network in listOf("signet", "testnet4", "regtest", "testnet")) {
            IndexJson.proxyMain(document.replace("\"network\":\"main\"", "\"network\":\"$network\""), locators) shouldBe
                false
        }
    }

    @Test
    fun `path body matches the one-leaf and three-leaf answers`() {
        val leaf = ByteArray(32) { 0x11 }
        val bytes = PathBody.encodeSingleLeaf(leaf)
        bytes.size shouldBe 146
        hex(bytes) shouldBe ONE_LEAF
        PathBody.validate(bytes, leaf) shouldBe true
        PathBody.validate(bytes, ByteArray(32) { 0x99.toByte() }) shouldBe false

        val three = PathBody.encodeSortedTree(
            arrayOf(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }, ByteArray(32) { 0x33 }),
            ByteArray(32) { 0x22 },
        )
        val rootAt = 42 + 2 * 32
        hex(three.copyOfRange(rootAt, rootAt + 32)) shouldBe
            "9c9a3f601444d1adc90e749e6a7972ea850c3029d2e8504ddfdaf111e4ecac90"
        PathBody.validate(three, ByteArray(32) { 0x22 }) shouldBe true
    }

    @Test
    fun `a sample object is in shape and a wide integer still decodes`() {
        val hike = HikeObject.sample()
        val bytes = HikeObject.encode(hike)
        val measured = HikeObject.readMeasured(bytes)
        measured shouldNotBe null
        measured!!.shape shouldBe true
        measured.hike.stages.size shouldBe 18
        bytes.copyOfRange(0, 2).toList() shouldBe listOf(0xa9.toByte(), 0x64.toByte())

        val wide = HikeObject.encodeWide(hike)
        wide.contentEquals(bytes) shouldBe false
        val again = HikeObject.readMeasured(wide)
        again!!.shape shouldBe true
        again.hike.stages[0].legCount shouldBe 1L
        again.hike.stages[0].beaconHeight shouldBe 1L
    }

    @Test
    fun `a bad object is invalid and a short hike does not stop at the first beacon`() {
        VerifyAny.finish(ByteArray(0), IndexSource { _ -> error("read") }, Draw.Entropy { _ -> error("drew") }) shouldBe
            VerifyAny.Word.INVALID

        val heights = mutableListOf<Long>()
        val measurements = HikeVerify.verify(
            quiet(2),
            IndexSource { height ->
                heights += height
                null
            },
            Draw.Entropy { _ -> error("drew") },
        )
        heights shouldBe listOf(1L, 2L)
        measurements.shape shouldBe false
        measurements.checkpoints!!.mismatch shouldBe true
        measurements.checkpoints!!.beaconMatchesIndex.toList() shouldBe listOf(false, false)
        measurements.waypoints shouldBe null
        measurements.legRate shouldBe HikeVerify.LegRate.NOT_READ
        measurements.pathsIncluded shouldBe 0L
        measurements.sixLegs.kind shouldBe HikeVerify.SixKind.COULD_NOT_DRAW
        measurements.stages!!.last().gap shouldBe HikeVerify.GapKind.NOT_DEFINED
        HikeVerify.admit(measurements) shouldBe false
        VerifyAny.finish(quiet(2), IndexSource { _ -> null }, Draw.Entropy { _ -> error("drew") }) shouldBe
            VerifyAny.Word.INVALID
    }

    @Test
    fun `an index fetch failure is not a verdict and does not draw`() {
        var drew = false
        shouldThrow<IndexUnreachable> {
            HikeVerify.verify(
                quiet(2),
                IndexSource { throw IndexUnreachable() },
                Draw.Entropy {
                    drew = true
                    error("drew")
                },
            )
        }
        drew shouldBe false
        shouldThrow<IndexUnreachable> {
            VerifyAny.finish(quiet(2), IndexSource { throw IndexUnreachable() }, Draw.Entropy { _ -> error("drew") })
        }
    }

    @Test
    fun `an unread leg rate does not fail and the stored gap is not section 15`() {
        val bytes = quiet(2)
        val measurements = HikeVerify.verify(
            bytes,
            IndexSource { height ->
                val beacon = ByteArray(32)
                beacon[0] = height.toByte()
                indexDocument(beacon, height, 1_000L * height)
            },
            Draw.Entropy { _ -> error("drew") },
        )
        measurements.checkpoints!!.mismatch shouldBe false
        measurements.legRate shouldBe HikeVerify.LegRate.NOT_READ
        measurements.stages!![0].gap shouldBe HikeVerify.GapKind.MILLIS
        measurements.stages!![0].rate shouldBe HikeVerify.RateKind.THOUSANDTHS
        measurements.stages!![1].gap shouldBe HikeVerify.GapKind.NOT_DEFINED
        measurements.stages!![1].rate shouldBe HikeVerify.RateKind.NOT_DEFINED
        HikeVerify.admit(measurements) shouldBe false
    }

    @Test
    fun `section 15 fails the recorded booleans and passes an unread leg rate`() {
        val passing = passing()
        HikeVerify.admit(passing) shouldBe true

        HikeVerify.admit(copy(passing, shape = false)) shouldBe false
        val mismatched = passing.checkpoints!!.let { checkpoints ->
            checkpoints.beaconMatchesIndex[0] = false
            HikeVerify.Checkpoints(checkpoints.beaconMatchesIndex, checkpoints.digestMatches, true)
        }
        HikeVerify.admit(
            HikeVerify.Measurements(
                true,
                mismatched,
                passing.waypoints,
                HikeVerify.LegRate.NOT_READ,
                0L,
                passing.sixLegs,
                null,
            ),
        ) shouldBe false

        val badPlant = HikeVerify.Waypoints(true, BooleanArray(17), 18L, true)
        HikeVerify.admit(measurements(passing, waypoints = badPlant)) shouldBe false
        val badSuccessor = HikeVerify.Waypoints(true, BooleanArray(17).also { it[4] = true }, 0L, false)
        HikeVerify.admit(measurements(passing, waypoints = badSuccessor)) shouldBe false
        val badWalk = HikeVerify.SixLegs(
            HikeVerify.SixKind.REWALKED,
            longArrayOf(0, 1, 2, 3, 4, 5),
            booleanArrayOf(true, true, false, true, true, true),
        )
        HikeVerify.admit(measurements(passing, six = badWalk)) shouldBe false
        HikeVerify.admit(
            measurements(passing, six = HikeVerify.SixLegs(HikeVerify.SixKind.COULD_NOT_DRAW, null, null)),
        ) shouldBe false
        HikeVerify.admit(
            measurements(passing, six = HikeVerify.SixLegs(HikeVerify.SixKind.DRAW_NOT_OBTAINED, null, null)),
        ) shouldBe false
        HikeVerify.admit(
            measurements(passing, six = HikeVerify.SixLegs(HikeVerify.SixKind.NOT_OBTAINED, null, null)),
        ) shouldBe true
    }

    private fun passing(): HikeVerify.Measurements =
        HikeVerify.Measurements(
            true,
            HikeVerify.Checkpoints(BooleanArray(18) { true }, Array(18) { booleanArrayOf(true) }, false),
            HikeVerify.Waypoints(true, BooleanArray(17), 18L, false),
            HikeVerify.LegRate.NOT_READ,
            0L,
            HikeVerify.SixLegs(
                HikeVerify.SixKind.REWALKED,
                longArrayOf(0, 1, 2, 3, 4, 5),
                BooleanArray(6) { true },
            ),
            null,
        )

    private fun copy(measurements: HikeVerify.Measurements, shape: Boolean) =
        HikeVerify.Measurements(
            shape,
            measurements.checkpoints,
            measurements.waypoints,
            measurements.legRate,
            measurements.pathsIncluded,
            measurements.sixLegs,
            measurements.stages,
        )

    private fun measurements(
        base: HikeVerify.Measurements,
        waypoints: HikeVerify.Waypoints = base.waypoints!!,
        six: HikeVerify.SixLegs = base.sixLegs,
    ) = HikeVerify.Measurements(
        true,
        base.checkpoints,
        waypoints,
        HikeVerify.LegRate.NOT_READ,
        0L,
        six,
        null,
    )

    private fun quiet(stages: Int): ByteArray {
        val list = Array(stages) { index ->
            val beacon = ByteArray(32)
            beacon[0] = (index + 1).toByte()
            HikeObject.Stage(beacon, index + 1L, 0L, ByteArray(32), ByteArray(0), emptyArray(), emptyArray())
        }
        return HikeObject.encode(HikeObject.Hike(ByteArray(32) { 0x11 }, list[0].beacon.copyOf(), list))
    }

    private fun hikeWithLegs(legs: Int): HikeObject.Hike {
        val stages = Array(legs) { index ->
            HikeObject.Stage(
                ByteArray(32),
                index + 1L,
                1L,
                ByteArray(32),
                ByteArray(0),
                emptyArray(),
                emptyArray(),
            )
        }
        return HikeObject.Hike(ByteArray(32), ByteArray(32), stages)
    }

    private fun indexDocument(
        natural: ByteArray,
        height: Long,
        timestamp: Long,
    ): String {
        val display = ByteArray(32)
        for (i in 0 until 32) {
            display[i] = natural[31 - i]
        }
        return """{"network":"main","header":{"hash":"${hex(display)}","height":$height,""" +
            """"timestamp_unix_ms":$timestamp},"plant":null}"""
    }

    private fun locators(): PathBody.Locators {
        val txid = ByteArray(32) { 0x44 }
        txid[0] = 0x01
        txid[31] = 0xfe.toByte()
        val block = ByteArray(32) { 0x55 }
        block[0] = 0x10
        block[31] = 0x90.toByte()
        return PathBody.Locators(ByteArray(32) { 0xab.toByte() }, txid, 2, block, 123_456)
    }

    private fun proxyDocument(locators: PathBody.Locators): String {
        fun display(bytes: ByteArray): String {
            val reversed = ByteArray(32)
            for (i in 0 until 32) {
                reversed[i] = bytes[31 - i]
            }
            return hex(reversed)
        }
        val root = hex(locators.root)
        val txid = display(locators.txid)
        val blockHash = display(locators.blockHash)
        return """{"version":2,"network":"main","header":{"hash":"${"ab".repeat(32)}","height":1},""" +
            """"plant":{"root":"$root","tree_size":99,"txid":"$txid","vout":${locators.vout},""" +
            """"block_hash":"$blockHash","block_height":${locators.blockHeight}}}"""
    }

    private fun le(value: Long): ByteArray {
        val out = ByteArray(8)
        var rest = value
        for (i in 0 until 8) {
            out[i] = rest.toByte()
            rest = rest ushr 8
        }
        return out
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private class Script(private val bytes: ByteArray) : Draw.Entropy {
        var pos: Int = 0

        override fun fill(dest: ByteArray): Boolean {
            if (pos + dest.size > bytes.size) {
                return false
            }
            bytes.copyInto(dest, 0, pos, pos + dest.size)
            pos += dest.size
            return true
        }
    }

    private companion object {
        const val ONE_LEAF =
            "011111111111111111111111111111111111111111111111111111111111111111000000000000000100" +
                "b7959d170da4982cf2361b9b75c52b70c1b42596e6b1f40665068cfc82ede395" +
                "4444444444444444444444444444444444444444444444444444444444444444" +
                "0000000255555555555555555555555555555555555555555555555555555555555555550001e240"
    }
}
