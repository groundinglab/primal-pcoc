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
        state = reduce(state, parseProduceEvent("""{"kind":"seed","height":1000}""")!!)
        state.seedHeight shouldBe 1000L
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
}
