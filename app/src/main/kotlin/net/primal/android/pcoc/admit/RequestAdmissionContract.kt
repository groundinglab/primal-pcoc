package net.primal.android.pcoc.admit

interface RequestAdmissionContract {
    data class UiState(
        val hasEnrolledObject: Boolean = false,
        val canSign: Boolean = false,
        val scanning: Boolean = true,
        val readingObject: Boolean = false,
        val readyUrl: String? = null,
        val hashHex: String? = null,
        val scanProblem: ScanProblem? = null,
        val submitting: Boolean = false,
        val confirming: Boolean = false,
        val outcome: Outcome? = null,
        val showWelcome: Boolean = true,
        val dontShowAgain: Boolean = false,
    )

    enum class ScanProblem {
        NotHttps,
        BareHash,
        BadHash,
        GateUrl,
        NotAServiceUrl,
        PasteRejected,
    }

    sealed interface Outcome {
        /** POST reached the scanned page. Not welcome, refused, or progress. */
        data object Delivered : Outcome

        /** The site named `bad_signature`. Not a policy refusal. */
        data object BadSignature : Outcome

        /** The POST did not arrive. Not a policy refusal. */
        data object Failed : Outcome

        data class KeyMismatch(
            val loginPrefix: String,
            val filePrefix: String,
        ) : Outcome

        data object ObjectKeyUnreadable : Outcome

        data object NotSent : Outcome
    }

    sealed class UiEvent {
        data class QrCodeDetected(val value: String) : UiEvent()

        data class CodePasted(val value: String) : UiEvent()

        data object ScanAgain : UiEvent()

        data object RequestSign : UiEvent()

        data object DismissSign : UiEvent()

        data object SignAndSubmit : UiEvent()

        data class DontShowAgainChanged(val checked: Boolean) : UiEvent()

        data object EnterSignIn : UiEvent()
    }
}
