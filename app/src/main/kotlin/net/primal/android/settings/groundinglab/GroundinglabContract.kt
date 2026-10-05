package net.primal.android.settings.groundinglab

import android.net.Uri

interface GroundinglabContract {

    data class UiState(
        val stage: Stage = Stage.Ready,
        val objectByteCount: Int? = null,
        val tipHash: String? = null,
        val signingIn: Boolean = false,
        val decision: String? = null,
        val error: SignInError? = null,
    ) {
        val canSignIn: Boolean = objectByteCount != null && tipHash != null && !signingIn
    }

    enum class Stage {
        Ready,
        Scan,
    }

    sealed class SignInError {
        data object EmptyFile : SignInError()
        data object UnreadableFile : SignInError()
        data object NotTipHash : SignInError()
        data object ProducerKeyRequired : SignInError()
        data object EmptyBody : SignInError()
        data object MissingDecision : SignInError()
        data object Transport : SignInError()
    }

    sealed class UiEvent {
        data class ImportFile(val uri: Uri) : UiEvent()
        data object OpenScanner : UiEvent()
        data object CloseScanner : UiEvent()
        data class QrTextDetected(val value: String) : UiEvent()
        data object SignIn : UiEvent()
    }
}
