package net.primal.android.pcoc.verify

import android.net.Uri
import net.primal.android.pcoc.hike.VerifyAny

interface VerifyPcocContract {
    data class UiState(
        val busy: Boolean = false,
        val stage: Stage? = null,
        val legsDone: Int = 0,
        val legsTotal: Int = 6,
        val stepDone: Int = 0,
        val stepTotal: Int = 0,
        val word: VerifyAny.Word? = null,
        val notice: Notice? = null,
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
    }

    sealed class UiEvent {
        data class FilePicked(val uri: Uri) : UiEvent()

        data object Cancel : UiEvent()
    }
}
