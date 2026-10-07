package net.primal.android.pcoc.verify

import net.primal.android.R
import net.primal.android.pcoc.hike.VerifyAny
import net.primal.android.pcoc.verify.VerifyPcocContract.MeasureRow
import net.primal.android.pcoc.verify.VerifyPcocContract.Notice
import net.primal.android.pcoc.verify.VerifyPcocContract.UiState

/** Screen rows for one measurement record. Leg rate "not read" carries the note. */
internal fun VerifyAny.Record.toRows(): List<MeasureRow> =
    listOf(
        MeasureRow(title = R.string.pcoc_verify_row_profile, found = profile),
        MeasureRow(title = R.string.pcoc_verify_row_shape, found = shape),
        MeasureRow(title = R.string.pcoc_verify_row_checkpoints, found = checkpoints),
        MeasureRow(title = R.string.pcoc_verify_row_waypoints, found = waypoints),
        MeasureRow(
            title = R.string.pcoc_verify_row_leg_rate,
            found = legRate,
            rateUnread = legRate == VerifyAny.NOT_READ,
        ),
        MeasureRow(title = R.string.pcoc_verify_row_six_leg, found = sixLeg),
    )

/**
 * A record shows the rows. Unread bytes show the couldn't-read notice.
 * A run that did not finish shows that notice. Neither notice is a policy verdict.
 */
internal fun measurementUiState(record: VerifyAny.Record?, unread: Boolean): UiState {
    if (record != null) {
        return UiState(recorded = true, rows = record.toRows())
    }
    return UiState(notice = if (unread) Notice.CouldNotRead else Notice.DidNotFinish)
}
