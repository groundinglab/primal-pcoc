package net.primal.android.pcoc.verify

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import net.primal.android.R
import net.primal.android.pcoc.hike.VerifyAny
import net.primal.android.pcoc.verify.VerifyPcocContract.Notice
import org.junit.Test

class VerifyRecordTest {

    @Test
    fun `rows follow the measurement record and note an unread leg rate`() {
        val rows = sample().toRows()
        rows.map { it.title } shouldBe listOf(
            R.string.pcoc_verify_row_profile,
            R.string.pcoc_verify_row_shape,
            R.string.pcoc_verify_row_checkpoints,
            R.string.pcoc_verify_row_waypoints,
            R.string.pcoc_verify_row_leg_rate,
            R.string.pcoc_verify_row_six_leg,
        )
        rows.map { it.found } shouldBe listOf(
            "production",
            "match",
            "mismatch",
            "not read",
            "not read",
            "not defined",
        )
        rows.map { it.rateUnread } shouldBe listOf(false, false, false, false, true, false)
    }

    @Test
    fun `a read leg rate does not carry the note`() {
        val rows = sample(legRate = "match").toRows()
        rows[4].rateUnread shouldBe false
    }

    @Test
    fun `a record is rows and unread or unfinished is a notice`() {
        val recorded = measurementUiState(sample(), unread = false)
        recorded.recorded shouldBe true
        recorded.notice shouldBe null
        recorded.rows.map { it.found } shouldBe listOf(
            "production",
            "match",
            "mismatch",
            "not read",
            "not read",
            "not defined",
        )

        val unread = measurementUiState(record = null, unread = true)
        unread.recorded shouldBe false
        unread.rows.shouldBeEmpty()
        unread.notice shouldBe Notice.CouldNotRead

        val unfinished = measurementUiState(record = null, unread = false)
        unfinished.recorded shouldBe false
        unfinished.rows.shouldBeEmpty()
        unfinished.notice shouldBe Notice.DidNotFinish
    }

    private fun sample(legRate: String = "not read") =
        VerifyAny.Record(
            "production",
            "match",
            "mismatch",
            "not read",
            legRate,
            "not defined",
        )
}
