package net.primal.android.pcoc.verify

import android.net.Uri
import androidx.annotation.StringRes

interface VerifyPcocContract {
    data class UiState(
        val busy: Boolean = false,
        val stopping: Boolean = false,
        val recorded: Boolean = false,
        val rows: List<MeasureRow> = emptyList(),
        val notice: Notice? = null,
    )

    data class MeasureRow(
        @StringRes val title: Int,
        val found: String,
    )

    enum class Notice {
        DidNotFinish,
        CouldNotRead,
        Unreadable,
    }

    sealed class UiEvent {
        data class FilePicked(val uri: Uri) : UiEvent()

        data object Cancel : UiEvent()
    }
}
