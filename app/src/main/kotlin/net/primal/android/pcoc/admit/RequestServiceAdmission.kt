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

    data object KeyMismatch : AdmissionSubmitOutcome()

    data object ObjectKeyUnreadable : AdmissionSubmitOutcome()
}

/**
 * Signs the 32 bytes of `h` and POSTs once.
 * No enrolled object, no producer key, a key that is not the object's `public_key`,
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
        if (ready == null || key == null || objectBytes == null) {
            AdmissionTrace.refused(
                reason = refuseReason(ready = ready, key = key, objectBytes = objectBytes),
                pubkeyMatch = null,
                hBytes = ready?.hashBytes?.size,
                target = ready?.exactUrl?.let(::admissionTarget),
            )
            return AdmissionSubmitOutcome.NotSubmitted
        }
        return postIfKeyMatches(ready = ready, key = key, accountHex = accountHex, objectBytes = objectBytes)
    }

    private fun postIfKeyMatches(
        ready: AdmissionQrParse.Ready,
        key: ByteArray,
        accountHex: String,
        objectBytes: ByteArray,
    ): AdmissionSubmitOutcome {
        val check = checkObjectKey(accountHex = accountHex, objectBytes = objectBytes)
        val target = admissionTarget(ready.exactUrl)
        return refusal(check = check, ready = ready, target = target)
            ?: signedPost(ready = ready, key = key, objectBytes = objectBytes, target = target)
    }

    private fun refusal(
        check: ObjectKeyCheck,
        ready: AdmissionQrParse.Ready,
        target: AdmissionTarget,
    ): AdmissionSubmitOutcome? {
        val outcome = when (check) {
            ObjectKeyCheck.Match -> null
            ObjectKeyCheck.Mismatch -> AdmissionSubmitOutcome.KeyMismatch
            ObjectKeyCheck.Unreadable -> AdmissionSubmitOutcome.ObjectKeyUnreadable
        }
        if (outcome != null) {
            val reason = if (check == ObjectKeyCheck.Mismatch) "key-mismatch" else "object-key-unreadable"
            AdmissionTrace.refused(
                reason = reason,
                pubkeyMatch = false,
                hBytes = ready.hashBytes.size,
                target = target,
            )
        }
        return outcome
    }

    private fun signedPost(
        ready: AdmissionQrParse.Ready,
        key: ByteArray,
        objectBytes: ByteArray,
        target: AdmissionTarget,
    ): AdmissionSubmitOutcome {
        AdmissionTrace.sign(pubkeyMatch = true, hBytes = ready.hashBytes.size, target = target)
        val signature = signOrNull(privateKey = key, hashHex = ready.hashHex)
        return if (signature == null) {
            AdmissionTrace.refused(
                reason = "sign",
                pubkeyMatch = true,
                hBytes = ready.hashBytes.size,
                target = target,
            )
            AdmissionSubmitOutcome.Failed
        } else {
            poster.post(
                exactUrl = ready.exactUrl,
                objectBytes = objectBytes,
                signatureHex = signature,
            ).toOutcome()
        }
    }

    private fun AdmissionPostResult.toOutcome(): AdmissionSubmitOutcome =
        when (this) {
            is AdmissionPostResult.Decision -> AdmissionSubmitOutcome.Decision(
                decision = decision,
                verifierRan = verifierRan,
            )
            AdmissionPostResult.Failed, AdmissionPostResult.NotSent -> AdmissionSubmitOutcome.Failed
        }

    private fun signOrNull(privateKey: ByteArray, hashHex: String): String? =
        try {
            signHash(privateKey, hashHex)
        } catch (error: IllegalArgumentException) {
            Napier.w(error) { "Service admission was not signed." }
            null
        }
}

private fun refuseReason(
    ready: AdmissionQrParse.Ready?,
    key: ByteArray?,
    objectBytes: ByteArray?,
): String =
    when {
        ready == null -> "qr"
        key == null -> "no-login"
        objectBytes == null -> "no-object"
        else -> "not-sent"
    }
