package net.primal.android.pcoc.create

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

class CreatePcocReducerTest {

    @Test
    fun `stage events follow the reference callback order`() {
        var state = CreateUiState()
        state = reduce(state, parseProduceEvent("""{"kind":"started","output":"/tmp/hike.cbor"}""")!!)
        state.phase shouldBe ProducePhase.Running
        state.outputPath shouldBe "/tmp/hike.cbor"
        state = reduce(state, parseProduceEvent("""{"kind":"seed","height":1000,"time":1700000000}""")!!)
        state.seedHeight shouldBe 1000L
        state.seedTimeUnixS shouldBe 1700000000L
        produceHeadline(state, 1_700_000_000_000 + 16 * 60 * 1000) shouldBe
            "Started on block 1000 (mined 16 min ago)"
        state = reduce(state, parseProduceEvent("""{"kind":"terrain"}""")!!)
        state = reduce(state, parseProduceEvent("""{"kind":"leg","stage":0,"j":0,"stored":1}""")!!)
        state = reduce(state, parseProduceEvent("""{"kind":"leg","stage":0,"j":1,"stored":2}""")!!)
        state.legsTotal shouldBe 2
        state.legsInStage shouldBe 2
        state = reduce(
            state,
            parseProduceEvent("""{"kind":"stage_closed","stage":0,"legs":2,"height":1001}""")!!,
        )
        state.stagesClosed shouldBe 1
        state.stage shouldBe 1
        state.legsInStage shouldBe 0
        state = reduce(state, parseProduceEvent("""{"kind":"settling"}""")!!)
        state.phase shouldBe ProducePhase.Settling
        state = reduce(
            state,
            parseProduceEvent("""{"kind":"written","path":"/tmp/hike.cbor","bytes":12,"sha256":"ab"}""")!!,
        )
        state.phase shouldBe ProducePhase.Written
        state.objectBytes shouldBe 12
        state.objectSha256 shouldBe "ab"
        state.stagesClosed shouldBe HIKE_STAGES
    }

    @Test
    fun `stop and failure write no object`() {
        val stopped = reduce(CreateUiState(phase = ProducePhase.Running), ProduceEvent.Stopped)
        stopped.phase shouldBe ProducePhase.Stopped
        stopped.objectSha256 shouldBe null

        val failed = reduce(
            CreateUiState(phase = ProducePhase.Running),
            parseProduceEvent("""{"kind":"failed","message":"clock read failed"}""")!!,
        )
        failed.phase shouldBe ProducePhase.Failed
        failed.message shouldBe "clock read failed"
        failed.objectBytes shouldBe null
    }

    @Test
    fun `produce says what it is doing from the first moment`() {
        val waiting = CreateUiState(phase = ProducePhase.Running)
        produceHeadline(waiting, 0) shouldBe "Waiting for latest Bitcoin header…"

        val started = reduce(
            waiting,
            parseProduceEvent("""{"kind":"seed","height":970726,"time":1000}""")!!,
        )
        produceHeadline(started, (1000 + 16 * 60) * 1000) shouldBe
            "Started on block 970726 (mined 16 min ago)"

        val running = reduce(
            started.copy(legsTotal = 4, tipHeight = 970727, stage = 0),
            parseProduceEvent("""{"kind":"leg","stage":0,"j":3,"stored":4}""")!!,
        )
        produceHeadline(running, 0) shouldBe "Stage 0 of 18 · from block 970726 · tip 970727"
    }

    @Test
    fun `header estimate counts headers still to come`() {
        headerEstimate(0) shouldBe "About 3 h 0 min left, from headers still to come."
        headerEstimate(17) shouldBe "About 10 min left, from headers still to come."
        headerEstimate(18) shouldBe null
    }

    @Test
    fun `object file name is UTC like the desktop`() {
        objectFileName(1_759_995_871_000) shouldContain "hike-"
        objectFileName(1_759_995_871_000).endsWith(".cbor") shouldBe true
    }

    @Test
    fun `stage 0 starts on the seed already in hand and is not posted`() {
        var state = reduce(CreateUiState(), parseProduceEvent("""{"kind":"started","output":"/tmp/hike.cbor"}""")!!)
        state = reduce(state, parseProduceEvent("""{"kind":"seed","height":1000}""")!!)
        state.stage shouldBe 0
        state.stagesClosed shouldBe 0
        state.waypoints shouldBe emptyList()

        state = reduce(
            state,
            parseProduceEvent("""{"kind":"stage_closed","stage":0,"legs":2,"height":1001}""")!!,
        )
        state.stagesClosed shouldBe 1
        state.openBeaconHeight shouldBe 1001L
        state.closedStages shouldBe listOf(ClosedStage(stage = 0, blockHeight = 1001))
        state.waypoints shouldBe emptyList()

        state = reduce(state, parseProduceEvent("""{"kind":"posting","beacon_height":1001}""")!!)
        state.waypoints shouldBe listOf(WaypointRow(index = 1, status = WaypointStatus.Posted))
        closedStageLabel(state.closedStages.single()) shouldBe "Stage 0 · 1,001"
        waypointLabel(state.waypoints.single()) shouldBe "W_1 · posted"
    }

    @Test
    fun `a paths report marks fetched waypoints anchored and skips the rest`() {
        var state = CreateUiState()
        repeat(17) { index ->
            state = reduce(state, ProduceEvent.Posting(beaconHeight = 1_000L + index))
        }
        state.waypoints.map { it.index } shouldBe (1..17).toList()
        state = reduce(
            state,
            ProduceEvent.Paths(lines = listOf("path_skip 2", "path_skip 5", "not fetched by design 17")),
        )
        state.waypoints.first { it.index == 1 }.status shouldBe WaypointStatus.Anchored
        state.waypoints.first { it.index == 2 }.status shouldBe WaypointStatus.Skipped
        state.waypoints.first { it.index == 5 }.status shouldBe WaypointStatus.Skipped
        state.waypoints.first { it.index == 16 }.status shouldBe WaypointStatus.Anchored
        state.waypoints.first { it.index == 17 }.status shouldBe WaypointStatus.NotFetched
        waypointLabel(state.waypoints.first { it.index == 1 }) shouldBe "W_1 · anchored"
        waypointLabel(state.waypoints.first { it.index == 17 }) shouldBe "W_17 · not fetched by design"

        state = reduce(
            state,
            ProduceEvent.Written(path = "/tmp/hike.cbor", bytes = 4096, sha256 = "ab"),
        )
        affirmation(state.copy(legsTotal = 100)) shouldBe
            "18 of 18 stages completed. 100 legs. 14 waypoints anchored, 2 skipped. " +
            "1 not fetched by design. SHA-256 ab. 4,096 bytes."
    }

    @Test
    fun `an empty paths report does not call a waypoint anchored`() {
        val posted = reduce(CreateUiState(), ProduceEvent.Posting(beaconHeight = 10))
        val same = reduce(posted, ProduceEvent.Paths(lines = emptyList()))
        same.waypoints.single().status shouldBe WaypointStatus.Posted
    }

    @Test
    fun `leg rate uses the recent ticks`() {
        var state = reduce(CreateUiState(), ProduceEvent.Leg(stage = 0, stored = 1), nowMs = 1_000)
        state.legRatePerSecond shouldBe null
        state = reduce(state, ProduceEvent.Leg(stage = 0, stored = 2), nowMs = 3_000)
        state.legRatePerSecond shouldBe 0.5
        formatLegRate(state.legRatePerSecond!!) shouldBe "0.50 legs/s"

        val ignored = reduce(CreateUiState(), ProduceEvent.Leg(stage = 0, stored = 1), nowMs = 0)
        ignored.legTicks shouldBe emptyList()

        var ticks = emptyList<Long>()
        repeat(LEG_RATE_TICKS + 1) { index ->
            ticks = noteLegTick(ticks, index.toLong() + 1)
        }
        ticks.size shouldBe LEG_RATE_TICKS
        ticks.first() shouldBe 2L
    }
}
