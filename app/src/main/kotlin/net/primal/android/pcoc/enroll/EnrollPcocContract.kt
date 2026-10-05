package net.primal.android.pcoc.enroll

import android.net.Uri

interface EnrollPcocContract {
    data class UiState(
        val stored: Boolean = false,
        val busy: Boolean = false,
        val confirmClear: Boolean = false,
        val notice: Notice? = null,
    )

    enum class Notice {
        Stored,
        Unreadable,
        NotStored,
        Cleared,
    }

    sealed class UiEvent {
        data class FilePicked(val uri: Uri) : UiEvent()

        data object RequestClear : UiEvent()

        data object ConfirmClear : UiEvent()

        data object DismissClear : UiEvent()
    }
}
