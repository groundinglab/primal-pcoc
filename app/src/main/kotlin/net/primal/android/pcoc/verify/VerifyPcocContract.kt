package net.primal.android.pcoc.verify

import android.net.Uri
import androidx.annotation.StringRes

interface VerifyPcocContract {
    data class UiState(
        val busy: Boolean = false,
        val stage: Stage? = null,
        val legsDone: Int = 0,
        val legsTotal: Int = 6,
        val stepDone: Int = 0,
        val stepTotal: Int = 0,
        val recorded: Boolean = false,
        val rows: List<MeasureRow> = emptyList(),
        val notice: Notice? = null,
    )

    data class MeasureRow(
        @StringRes val title: Int,
        val found: String,
        val rateUnread: Boolean = false,
    )

    enum class Stage {
        File,
        Index,
        Terrain,
        Checks,
        Checkpoints,
        Waypoints,
        Paths,
        Legs,
    }

    enum class Notice {
        IndexUnreachable,
        DidNotFinish,
        CouldNotRead,
    }

    sealed class UiEvent {
        data class FilePicked(val uri: Uri) : UiEvent()

        data object Cancel : UiEvent()
    }
}
