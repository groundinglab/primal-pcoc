package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier
import net.primal.android.pcoc.enroll.EnrolledPcocStore

sealed class AdmissionSubmitOutcome {
    data object Delivered : AdmissionSubmitOutcome()

    data object BadSignature : AdmissionSubmitOutcome()

    data object Failed : AdmissionSubmitOutcome()

    data object NotSubmitted : AdmissionSubmitOutcome()

    data class KeyMismatch(
        val loginPrefix: String,
        val filePrefix: String,
    ) : AdmissionSubmitOutcome()

    data object ObjectKeyUnreadable : AdmissionSubmitOutcome()
}

/**
 * Signs the 32 bytes of `h` and POSTs once.
 * No enrolled object, no producer key, a public key that is not this login,
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
        val objectBytes = if (ready != null && key != null) enrolledObject(store, accountHex) else null
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
        val report = objectKeyReport(
            accountHex = accountHex,
            signingKey = signingPublicKey(key),
            objectBytes = objectBytes,
        )
        val target = admissionTarget(ready.exactUrl)
        return refusal(report = report, ready = ready, target = target)
            ?: signedPost(
                ready = ready,
                key = key,
                objectBytes = objectBytes,
                target = target,
                report = report,
            )
    }

    private fun refusal(
        report: ObjectKeyReport,
        ready: AdmissionQrParse.Ready,
        target: AdmissionTarget,
    ): AdmissionSubmitOutcome? {
        val outcome = when (report.check) {
            ObjectKeyCheck.Match -> null
            ObjectKeyCheck.Mismatch -> AdmissionSubmitOutcome.KeyMismatch(
                loginPrefix = report.loginPrefix.orEmpty(),
                filePrefix = report.filePrefix.orEmpty(),
            )
            ObjectKeyCheck.Unreadable -> AdmissionSubmitOutcome.ObjectKeyUnreadable
        }
        if (outcome != null) {
            val reason = if (report.check == ObjectKeyCheck.Mismatch) "key-mismatch" else "object-key-unreadable"
            AdmissionTrace.refused(
                reason = reason,
                pubkeyMatch = false,
                hBytes = ready.hashBytes.size,
                target = target,
                keys = report,
            )
        }
        return outcome
    }

    private fun signedPost(
        ready: AdmissionQrParse.Ready,
        key: ByteArray,
        objectBytes: ByteArray,
        target: AdmissionTarget,
        report: ObjectKeyReport,
    ): AdmissionSubmitOutcome {
        AdmissionTrace.sign(pubkeyMatch = true, hBytes = ready.hashBytes.size, target = target, keys = report)
        val signature = signOrNull(privateKey = key, hashHex = ready.hashHex)
        return if (signature == null) {
            AdmissionTrace.refused(
                reason = "sign",
                pubkeyMatch = true,
                hBytes = ready.hashBytes.size,
                target = target,
                keys = report,
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
            AdmissionPostResult.Delivered -> AdmissionSubmitOutcome.Delivered
            AdmissionPostResult.BadSignature -> AdmissionSubmitOutcome.BadSignature
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
