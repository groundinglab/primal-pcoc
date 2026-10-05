package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier
import net.primal.android.pcoc.enroll.EnrolledPcocStore

sealed class AdmissionSubmitOutcome {
    data class Decision(
        val decision: AdmissionDecision,
        val verifierRan: Boolean? = null,
    ) : AdmissionSubmitOutcome()

    data object Failed : AdmissionSubmitOutcome()

    data object NotSubmitted : AdmissionSubmitOutcome()
}

/**
 * Signs the 32 bytes of `h` and POSTs once. No enrolled object, no producer key,
 * or a QR that is not a ready HTTPS URL: nothing is submitted.
 */
class RequestServiceAdmission(
    private val store: EnrolledPcocStore,
    private val poster: AdmissionPoster,
    private val signHash: (ByteArray, String) -> String = AdmissionSigner::sign,
) {
    fun submit(
        exactUrl: String,
        accountHex: String,
        privateKey: ByteArray?,
    ): AdmissionSubmitOutcome {
        val ready = AdmissionQr.parse(exactUrl) as? AdmissionQrParse.Ready
        val key = privateKey?.takeIf { it.size == PRODUCER_KEY_SIZE }
        val objectBytes = if (ready != null && key != null) store.read(accountHex) else null
        return postIfReady(ready = ready, key = key, objectBytes = objectBytes)
    }

    private fun postIfReady(
        ready: AdmissionQrParse.Ready?,
        key: ByteArray?,
        objectBytes: ByteArray?,
    ): AdmissionSubmitOutcome {
        if (ready == null || key == null || objectBytes == null) {
            return AdmissionSubmitOutcome.NotSubmitted
        }
        val signature = signOrNull(privateKey = key, hashHex = ready.hashHex)
        val posted = signature?.let { signed ->
            poster.post(
                exactUrl = ready.exactUrl,
                objectBytes = objectBytes,
                signatureHex = signed,
            )
        }
        return posted.toOutcome()
    }

    private fun AdmissionPostResult?.toOutcome(): AdmissionSubmitOutcome =
        when (this) {
            is AdmissionPostResult.Decision -> AdmissionSubmitOutcome.Decision(
                decision = decision,
                verifierRan = verifierRan,
            )
            AdmissionPostResult.Failed, AdmissionPostResult.NotSent -> AdmissionSubmitOutcome.Failed
            null -> AdmissionSubmitOutcome.Failed
        }

    private fun signOrNull(privateKey: ByteArray, hashHex: String): String? =
        try {
            signHash(privateKey, hashHex)
        } catch (error: IllegalArgumentException) {
            Napier.w(error) { "Service admission was not signed." }
            null
        }
}
