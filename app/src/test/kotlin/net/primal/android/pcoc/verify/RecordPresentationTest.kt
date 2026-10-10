package net.primal.android.pcoc.verify

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.primal.android.pcoc.bridge.indexStatus
import net.primal.android.pcoc.bridge.parseVerifyOutcome
import net.primal.android.pcoc.bridge.VerifyNativeOutcome
import org.junit.Test

class RecordPresentationTest {

    @Test
    fun `a section 14 record lists measurements`() {
        val presented = presentRecord(SAMPLE).shouldBeInstanceOf<Presented.Record>()
        presented.profile shouldBe "production"
        presented.shape shouldBe "match"
        presented.seed shouldBe "match"
        presented.headers shouldBe "every one is not read"
        presented.checkpoints shouldBe "every one is mismatch"
        presented.waypoints shouldBe "every one is not read"
        presented.paths shouldBe "every one is skip · W_17 empty"
        presented.sixLeg shouldBe "not defined"
        presented.span shouldBe "not defined"
    }

    @Test
    fun `an earlier version is a measurement`() {
        val presented = presentRecord("""{"version":"earlier version","kind":"satoshi-hike-v1.35-object"}""")
        presented.shouldBeInstanceOf<Presented.Earlier>().kind shouldBe "satoshi-hike-v1.35-object"
    }

    @Test
    fun `index status names the host and the reads`() {
        indexStatus("https://index.pcoc.app", issued = 0, answered = 0, totalMs = 0) shouldBe
            "Portable index not read · index.pcoc.app"
        indexStatus("https://index.pcoc.app", issued = 2, answered = 0, totalMs = 0) shouldBe
            "Portable index did not answer · index.pcoc.app · 2 reads"
        indexStatus("https://index.pcoc.app", issued = 2, answered = 2, totalMs = 40) shouldBe
            "Portable index reachable · index.pcoc.app · 20 ms · 2 reads"
    }

    @Test
    fun `engine json distinguishes a record from an undecodable file`() {
        parseVerifyOutcome("""{"outcome":"unreadable"}""") shouldBe VerifyNativeOutcome.Unreadable
        val record = parseVerifyOutcome(
            """
            {"outcome":"record","record":"{\"profile\":\"production\"}\n","bytes":4,
            "sha256":"ab","issued":1,"answered":0,"total_ms":0,"index_url":"https://index.pcoc.app"}
            """.trimIndent().replace("\n", ""),
        ).shouldBeInstanceOf<VerifyNativeOutcome.Record>()
        record.bytes shouldBe 4
        record.sha256 shouldBe "ab"
        record.issued shouldBe 1
        record.indexUrl shouldBe "https://index.pcoc.app"
    }

    private companion object {
        val SAMPLE = """
            {
              "profile": "production",
              "shape": "match",
              "seed_value": "match",
              "headers": [{"index_comparison": "not read"}],
              "checkpoints": [{"legs": [{"result": "mismatch"}]}],
              "waypoints": [{"result": "not read"}],
              "paths": [
                {"g": 1, "path_state": "skip"},
                {"g": 17, "w17_path": "empty"}
              ],
              "six_leg": "not defined",
              "analysis": {"whole_hike_span": "not defined"}
            }
        """.trimIndent()
    }
}
