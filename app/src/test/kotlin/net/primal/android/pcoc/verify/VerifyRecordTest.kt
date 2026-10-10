package net.primal.android.pcoc.verify

import io.kotest.matchers.shouldBe
import net.primal.android.R
import net.primal.android.pcoc.verify.VerifyPcocContract.Notice
import org.junit.Test

class VerifyRecordTest {

    @Test
    fun `an unreadable file and an unfinished run are notices`() {
        notice(Notice.CouldNotRead)
        notice(Notice.Unreadable)
        notice(Notice.DidNotFinish)
    }

    private fun notice(notice: Notice) {
        val state = VerifyPcocContract.UiState(notice = notice)
        state.recorded shouldBe false
        state.rows.isEmpty() shouldBe true
    }

    @Test
    fun `row titles are the section 14 fields`() {
        listOf(
            R.string.pcoc_verify_row_profile,
            R.string.pcoc_verify_row_shape,
            R.string.pcoc_verify_row_headers,
            R.string.pcoc_verify_row_checkpoints,
            R.string.pcoc_verify_row_waypoints,
            R.string.pcoc_verify_row_paths,
            R.string.pcoc_verify_row_six_leg,
            R.string.pcoc_verify_row_index,
        ).distinct().size shouldBe 8
    }
}
