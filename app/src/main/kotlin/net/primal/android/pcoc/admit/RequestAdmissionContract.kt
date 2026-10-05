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
    )

    enum class ScanProblem {
        NotHttps,
        BareHash,
        BadHash,
        GateUrl,
        NotAServiceUrl,
    }

    sealed interface Outcome {
        data class FromGate(val decision: AdmissionDecision, val verifierRan: Boolean?) : Outcome

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

        data object ScanAgain : UiEvent()

        data object RequestSign : UiEvent()

        data object DismissSign : UiEvent()

        data object SignAndSubmit : UiEvent()
    }
}
