package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier

/**
 * One short sentence for each sign-in step, for the screen and for logcat.
 * [logLine] is the same words. The log tag is [SIGN_IN_LOG_TAG].
 */
data class SignInNotice(
    val userText: String,
    val logLine: String,
)

internal const val SIGN_IN_LOG_TAG = "SignIn"

internal object SignInLog {
    fun write(notice: SignInNotice) {
        Napier.i(tag = SIGN_IN_LOG_TAG) { notice.logLine }
    }
}

/**
 * Pass and fail copy for scan, sign, submit, the Gate result, and a PCOC DM.
 * Plain words. No nsec, no signature, no raw response body.
 */
internal object SignInNotices {
    fun scan(ready: Boolean, why: String = "", flush: Boolean = false): SignInNotice =
        when {
            ready -> notice("Link recognised.")
            flush -> notice("Not a Reset link. $why")
            else -> notice("Not a sign-in link. $why")
        }

    fun sign(ok: Boolean): SignInNotice = notice(if (ok) "Signed." else "Signing failed.")

    fun submit(
        failure: DeliveryFailure? = null,
        httpCode: Int? = null,
        kind: GateLinkKind = GateLinkKind.SignIn,
    ): SignInNotice {
        if (failure == null) return notice("Sent to admission.groundinglab.io.")
        val text = when (failure) {
            DeliveryFailure.NoConnection -> "Could not reach admission.groundinglab.io."
            DeliveryFailure.TimedOut -> "Timed out reaching admission.groundinglab.io."
            DeliveryFailure.NotSent -> if (kind == GateLinkKind.Flush) {
                "The reset was not sent."
            } else {
                "The sign-in was not sent."
            }
            DeliveryFailure.Redirected,
            DeliveryFailure.UnexpectedResponse,
            -> httpText(httpCode)
        }
        return notice(text)
    }

    /** `accepted`, `expired`, or a Gate refusal token. Pending and unknown stay quiet. */
    fun gate(token: String): SignInNotice? = when (token) {
        "accepted" -> notice("Accepted.")
        "expired" -> notice("Expired.")
        "pending", "" -> null
        else -> GATE_WORDS[token]?.let { words -> notice("Refused. $words.") }
    }

    fun dm(parsed: Boolean): SignInNotice =
        notice(if (parsed) "PCOC .cbor DM received." else "Failed to parse the PCOC .cbor DM.")

    /** Sentences after a link was recognised. Local stops before signing add none. */
    fun trail(
        outcome: AdmissionSubmitOutcome,
        kind: GateLinkKind = GateLinkKind.SignIn,
    ): List<SignInNotice> = when (outcome) {
        AdmissionSubmitOutcome.Submitted -> listOf(sign(ok = true), submit(kind = kind))
        AdmissionSubmitOutcome.SigningFailed -> listOf(sign(ok = false))
        AdmissionSubmitOutcome.BadSignature -> sentThen(gate("bad_signature"), kind)
        AdmissionSubmitOutcome.Malformed -> sentThen(gate("malformed"), kind)
        is AdmissionSubmitOutcome.DeliveryFailed -> listOf(
            sign(ok = true),
            submit(failure = outcome.failure, httpCode = outcome.httpCode, kind = kind),
        )
        AdmissionSubmitOutcome.NoObject,
        AdmissionSubmitOutcome.WrongLogin,
        is AdmissionSubmitOutcome.KeyMismatch,
        AdmissionSubmitOutcome.ObjectKeyUnreadable,
        -> emptyList()
    }

    private fun sentThen(gateNotice: SignInNotice?, kind: GateLinkKind): List<SignInNotice> {
        val sent = listOf(sign(ok = true), submit(kind = kind))
        return if (gateNotice == null) sent else sent + gateNotice
    }

    private fun httpText(httpCode: Int?): String = if (httpCode == null) {
        "admission.groundinglab.io returned an HTTP error."
    } else {
        "admission.groundinglab.io returned HTTP $httpCode."
    }

    private fun notice(text: String) = SignInNotice(userText = text, logLine = text)

    private val GATE_WORDS = mapOf(
        "malformed" to "malformed",
        "bad_signature" to "bad signature",
        "too_short" to "too short",
        "rejected" to "rejected",
        "earlier_version" to "earlier version",
        "too_few_waypoints_anchored" to "too few waypoints anchored",
    )
}
