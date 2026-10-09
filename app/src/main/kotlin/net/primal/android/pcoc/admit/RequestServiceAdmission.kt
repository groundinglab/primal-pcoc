package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier
import net.primal.android.pcoc.enroll.EnrolledPcocStore

sealed class AdmissionSubmitOutcome {
    data object Submitted : AdmissionSubmitOutcome()

    data object BadSignature : AdmissionSubmitOutcome()

    data object Malformed : AdmissionSubmitOutcome()

    data class DeliveryFailed(val failure: DeliveryFailure) : AdmissionSubmitOutcome()

    /** No stored object for this login. Nothing was signed or sent. */
    data object NoObject : AdmissionSubmitOutcome()

    /** This login holds no nsec. Nothing was signed or sent. */
    data object WrongLogin : AdmissionSubmitOutcome()

    /** The stored object's SHA-256 is not the link's `object`. Nothing was signed or sent. */
    data object ObjectNotHeld : AdmissionSubmitOutcome()

    data class KeyMismatch(
        val loginPrefix: String,
        val filePrefix: String,
    ) : AdmissionSubmitOutcome()

    data object ObjectKeyUnreadable : AdmissionSubmitOutcome()
}

/** Signs SPEC §15.1 `SHA-256(challenge_bytes)` for one link and POSTs once. */
class RequestServiceAdmission(
    private val store: EnrolledPcocStore,
    private val poster: AdmissionPoster,
    private val signLink: (ByteArray, SignInLink) -> String = AdmissionSigner::sign,
) {
    fun submit(
        link: SignInLink,
        accountHex: String,
        privateKey: ByteArray?,
    ): AdmissionSubmitOutcome {
        val key = privateKey?.takeIf { it.size == PRODUCER_KEY_SIZE }
        val objectBytes = enrolledObject(store, accountHex)
        val plan = planLink(
            link = link,
            canSign = key != null,
            accountHex = accountHex,
            objectBytes = objectBytes,
            signingKey = key?.let(::signingPublicKey),
        )
        AdmissionTrace.link(followUp = plan.followUp.name, keys = plan.keys)
        return if (plan.followUp == LinkFollowUp.Submit && key != null && objectBytes != null) {
            signedPost(link = link, key = key, objectBytes = objectBytes)
        } else {
            AdmissionTrace.refused(reason = plan.followUp.name, keys = plan.keys)
            plan.toLocalStop()
        }
    }

    private fun signedPost(
        link: SignInLink,
        key: ByteArray,
        objectBytes: ByteArray,
    ): AdmissionSubmitOutcome {
        val signature = try {
            signLink(key, link)
        } catch (error: IllegalArgumentException) {
            Napier.w(error) { "Sign-in was not signed." }
            return AdmissionSubmitOutcome.DeliveryFailed(DeliveryFailure.NotSent)
        }
        return when (val result = poster.post(link = link, objectBytes = objectBytes, signatureHex = signature)) {
            AdmissionPostResult.Submitted -> AdmissionSubmitOutcome.Submitted
            AdmissionPostResult.BadSignature -> AdmissionSubmitOutcome.BadSignature
            AdmissionPostResult.Malformed -> AdmissionSubmitOutcome.Malformed
            is AdmissionPostResult.DeliveryFailed -> AdmissionSubmitOutcome.DeliveryFailed(result.failure)
        }
    }
}

private fun LinkPlan.toLocalStop(): AdmissionSubmitOutcome =
    when (followUp) {
        LinkFollowUp.ObjectNotHeld -> AdmissionSubmitOutcome.ObjectNotHeld
        LinkFollowUp.KeyMismatch -> AdmissionSubmitOutcome.KeyMismatch(
            loginPrefix = keys?.loginPrefix.orEmpty(),
            filePrefix = keys?.filePrefix.orEmpty(),
        )
        LinkFollowUp.ObjectKeyUnreadable -> AdmissionSubmitOutcome.ObjectKeyUnreadable
        LinkFollowUp.NoObject -> AdmissionSubmitOutcome.NoObject
        LinkFollowUp.Submit, LinkFollowUp.WrongLogin -> AdmissionSubmitOutcome.WrongLogin
    }
