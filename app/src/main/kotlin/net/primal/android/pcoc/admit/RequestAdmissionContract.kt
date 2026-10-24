package net.primal.android.pcoc.admit

interface RequestAdmissionContract {
    data class UiState(
        val hasEnrolledObject: Boolean = false,
        val canSign: Boolean = false,
        val scanning: Boolean = true,
        val origin: String? = null,
        val submitting: Boolean = false,
        val scanProblem: ScanProblem? = null,
        val localStop: LocalStop? = null,
        val sheet: Sheet? = null,
        val notices: List<String> = emptyList(),
        val showWelcome: Boolean = true,
        val dontShowAgain: Boolean = false,
        val action: GateLinkKind? = null,
        val previousSignings: PreviousSigningsState = PreviousSigningsState.Loading,
    )

    /** Previous signings for this login, from the public gate log. */
    sealed interface PreviousSigningsState {
        data object Loading : PreviousSigningsState

        data object Empty : PreviousSigningsState

        data object Unavailable : PreviousSigningsState

        data class Ready(val lines: List<String>) : PreviousSigningsState
    }

    enum class ScanProblem {
        NotASignInLink,
        NotAResetLink,
        RetiredCode,
    }

    /** Stopped on the phone. Nothing was signed and nothing was sent. */
    sealed interface LocalStop {
        data object NoObject : LocalStop

        data object WrongLogin : LocalStop

        data class KeyMismatch(
            val loginPrefix: String,
            val filePrefix: String,
        ) : LocalStop

        data object ObjectKeyUnreadable : LocalStop

        data object ObjectUnreadable : LocalStop
    }

    /** The one acknowledgement after the POST. */
    sealed interface Sheet {
        data object Submitted : Sheet

        data class CouldNotSubmit(val reason: CouldNotSubmitReason) : Sheet
    }

    enum class CouldNotSubmitReason {
        BadSignature,
        Malformed,
        NoConnection,
        TimedOut,
        Redirected,
        UnexpectedResponse,
        NotSent,
        SigningFailed,
    }

    sealed class UiEvent {
        data class QrCodeDetected(val value: String) : UiEvent()

        data class CodePasted(val value: String) : UiEvent()

        /** A verified app link. Same parser as scan and paste, and it skips the welcome. */
        data class AppLink(val value: String) : UiEvent()

        data object ScanAgain : UiEvent()

        data class DontShowAgainChanged(val checked: Boolean) : UiEvent()

        data object EnterSignIn : UiEvent()
    }
}
