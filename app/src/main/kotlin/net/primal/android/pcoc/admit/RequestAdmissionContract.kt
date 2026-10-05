package net.primal.android.pcoc.admit

interface RequestAdmissionContract {
    data class UiState(
        val hasEnrolledObject: Boolean = false,
        val canSign: Boolean = false,
        val scanning: Boolean = true,
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

    enum class Outcome {
        Accepted,
        Denied,
        Failed,
    }

    sealed class UiEvent {
        data class QrCodeDetected(val value: String) : UiEvent()

        data object ScanAgain : UiEvent()

        data object RequestSign : UiEvent()

        data object DismissSign : UiEvent()

        data object SignAndSubmit : UiEvent()
    }
}
