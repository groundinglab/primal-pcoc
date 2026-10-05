package net.primal.android.pcoc.hike

import io.kotest.matchers.shouldBe
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.Test

class HikeVerifyFileCheckTest {

    @Test
    fun `platform checkpoint hash matches the portable sha256 including an unaligned sample`() {
        val words = LongArray(HikeConst.SAMPLE_BYTES / 8 + 2) { index ->
            (index.toLong() * 0x9E3779B97F4A7C15UL.toLong()) xor (index.toLong() shl 7)
        }
        val room = TerrainRoom(words)
        try {
            val preimage = ByteArray(HikeConst.CHECKPOINT_PREIMAGE_LEN)
            val waypoint = ByteArray(32) { 0x5A }
            val leg = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
            Bind.fillCheckpointPreimage(room, preimage, ByteArray(32), waypoint, leg)
            assertSameDigest(preimage)

            val unaligned = ByteArray(32)
            unaligned[0] = 3
            Bind.fillCheckpointPreimage(room, preimage, unaligned, waypoint, leg)
            assertSameDigest(preimage)
            Bind.sampleOffset(unaligned) % 8 shouldBe 3
        } finally {
            room.release()
        }
    }

    @Test
    fun `stored checkpoint digests match and the file-check count advances`() {
        val publicKey = ByteArray(32) { 0x11 }
        val beacon = ByteArray(32)
        beacon[0] = 4
        val waypoint = ByteArray(32)
        waypoint[1] = 6
        val leg0 = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0)
        val leg1 = byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0)
        val room = Terrain.fill(Terrain.fillPin(Terrain.productionSeed(publicKey, beacon)))
        val checkpoint0: ByteArray
        val checkpoint1: ByteArray
        try {
            val preimage = ByteArray(HikeConst.CHECKPOINT_PREIMAGE_LEN)
            Bind.fillCheckpointPreimage(room, preimage, ByteArray(32), waypoint, leg0)
            checkpoint0 = Sha256.hash(preimage)
            Bind.fillCheckpointPreimage(room, preimage, checkpoint0, waypoint, leg1)
            checkpoint1 = Sha256.hash(preimage)
        } finally {
            room.release()
        }
        val stage = HikeObject.Stage(
            beacon,
            9L,
            2L,
            waypoint,
            ByteArray(0),
            arrayOf(checkpoint0, checkpoint1),
            arrayOf(leg0, leg1),
        )
        val bytes = HikeObject.encode(HikeObject.Hike(publicKey, beacon.copyOf(), arrayOf(stage)))
        val seen = mutableListOf<Pair<Int, Int>>()
        val logs = mutableListOf<String>()
        withFineLogs(logs) {
            val measurements = HikeVerify.verify(
                bytes,
                IndexSource { height -> indexDocument(beacon, height, 1_000L) },
                Draw.Entropy { _ -> error("drew") },
                object : VerifyProgress {
                    override fun indexReads() = Unit

                    override fun terrain() = Unit

                    override fun checks() = Unit

                    override fun leg(done: Int, total: Int) = Unit

                    override fun checkpoints(done: Int, total: Int) {
                        seen += done to total
                    }
                },
            )
            measurements.checkpoints!!.digestMatches[0].toList() shouldBe listOf(true, true)
            measurements.checkpoints!!.mismatch shouldBe false
            measurements.sixLegs.kind shouldBe HikeVerify.SixKind.COULD_NOT_DRAW
        }
        seen shouldBe listOf(0 to 2, 1 to 2, 2 to 2)
        logs.any { it.startsWith("file checks 2 records ") } shouldBe true
    }

    @Test
    fun `waypoint and path checks report their own steps`() {
        val opening = ByteArray(32)
        val stages = Array(HikeConst.STAGES) { index ->
            val beacon = ByteArray(32)
            beacon[0] = (index + 1).toByte()
            val waypoint = if (index == 0) opening else ByteArray(32).also { it[0] = index.toByte() }
            val path = if (index == 0) PathBody.encodeSingleLeaf(opening) else ByteArray(0)
            HikeObject.Stage(
                beacon,
                index + 1L,
                0L,
                waypoint,
                path,
                emptyArray(),
                emptyArray(),
            )
        }
        val bytes = HikeObject.encode(
            HikeObject.Hike(ByteArray(32) { 0x11 }, stages[0].beacon.copyOf(), stages),
        )
        val waypoints = mutableListOf<Pair<Int, Int>>()
        val paths = mutableListOf<Pair<Int, Int>>()
        val checkpoints = mutableListOf<Pair<Int, Int>>()
        var indexNotes = 0
        val logs = mutableListOf<String>()
        withFineLogs(logs) {
            HikeVerify.verify(
                bytes,
                IndexSource { _ -> null },
                Draw.Entropy { _ -> error("drew") },
                object : VerifyProgress {
                    override fun indexReads() {
                        indexNotes++
                    }

                    override fun terrain() = Unit

                    override fun checks() = Unit

                    override fun leg(done: Int, total: Int) = Unit

                    override fun checkpoints(done: Int, total: Int) {
                        checkpoints += done to total
                    }

                    override fun waypoints(done: Int, total: Int) {
                        waypoints += done to total
                    }

                    override fun paths(done: Int, total: Int) {
                        paths += done to total
                    }
                },
            )
        }
        checkpoints shouldBe emptyList()
        waypoints.first() shouldBe (0 to (HikeConst.STAGES - 1))
        waypoints.last() shouldBe ((HikeConst.STAGES - 1) to (HikeConst.STAGES - 1))
        waypoints.size shouldBe HikeConst.STAGES
        paths shouldBe listOf(0 to 1, 1 to 1)
        indexNotes shouldBe 2
        logs.any { it.startsWith("waypoints ${HikeConst.STAGES - 1} records ") } shouldBe true
        logs.any { it.startsWith("paths 1 records ") } shouldBe true
    }

    private fun assertSameDigest(preimage: ByteArray) {
        val expected = Sha256.hash(preimage)
        val opened = CheckpointHash.open()
        opened.usesPlatform() shouldBe true
        val platform = ByteArray(32)
        opened.digest(preimage, platform)
        platform.contentEquals(expected) shouldBe true
        val fallbackOut = ByteArray(32)
        CheckpointHash(null, Sha256()).digest(preimage, fallbackOut)
        fallbackOut.contentEquals(expected) shouldBe true
    }

    private fun withFineLogs(logs: MutableList<String>, block: () -> Unit) {
        val logger = Logger.getLogger("pcoc.hike")
        val previous = logger.level
        logger.level = Level.FINE
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.loggerName == "pcoc.hike") logs += record.message
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        handler.level = Level.FINE
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            logger.level = previous
        }
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
        val hash = display.joinToString("") { "%02x".format(it) }
        return """{"network":"main","header":{"hash":"$hash","height":$height,""" +
            """"timestamp_unix_ms":$timestamp},"plant":null}"""
    }
}
