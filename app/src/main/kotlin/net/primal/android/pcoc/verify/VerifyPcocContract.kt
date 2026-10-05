package net.primal.android.pcoc.verify

import android.net.Uri
import net.primal.android.pcoc.hike.VerifyAny

interface VerifyPcocContract {
    data class UiState(
        val busy: Boolean = false,
        val word: VerifyAny.Word? = null,
    )

    sealed class UiEvent {
        data class FilePicked(val uri: Uri) : UiEvent()
    }
}
