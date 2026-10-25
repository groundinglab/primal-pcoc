package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier
import net.primal.android.pcoc.enroll.EnrolledPcocStore

sealed class AdmissionSubmitOutcome {
    data object Submitted : AdmissionSubmitOutcome()

    data object BadSignature : AdmissionSubmitOutcome()

    data object Malformed : AdmissionSubmitOutcome()

    data class DeliveryFailed(
        val failure: DeliveryFailure,
        val httpCode: Int? = null,
    ) : AdmissionSubmitOutcome()

    /** BIP-340 signing threw. Nothing was sent. */
    data object SigningFailed : AdmissionSubmitOutcome()

    /** No stored object for this login. Nothing was signed or sent. */
    data object NoObject : AdmissionSubmitOutcome()

    /** This login holds no nsec. Nothing was signed or sent. */
    data object WrongLogin : AdmissionSubmitOutcome()

    data class KeyMismatch(
        val loginPrefix: String,
        val filePrefix: String,
    ) : AdmissionSubmitOutcome()

    data object ObjectKeyUnreadable : AdmissionSubmitOutcome()
}

/**
 * Signs one gate link and POSTs once.
 * Sign-in uses the enrolled object. Flush uses only this login's key.
 */
class RequestServiceAdmission(
    private val store: EnrolledPcocStore,
    private val poster: AdmissionPoster,
    private val flushPoster: FlushPoster = FlushPoster { _, _, _ ->
        AdmissionPostResult.DeliveryFailed(DeliveryFailure.NotSent)
    },
    private val signLink: (ByteArray, SignInLink, ByteArray) -> String = AdmissionSigner::sign,
    private val signFlush: (ByteArray, SignInLink, ByteArray) -> String = AdmissionSigner::signFlush,
) {
    fun submit(
        link: SignInLink,
        accountHex: String,
        privateKey: ByteArray?,
    ): AdmissionSubmitOutcome {
        if (link.kind == GateLinkKind.Flush) return submitFlush(link, privateKey)
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

    /** Reset needs the login's nsec. It does not read an enrolled object. */
    private fun submitFlush(link: SignInLink, privateKey: ByteArray?): AdmissionSubmitOutcome {
        val key = privateKey?.takeIf { it.size == PRODUCER_KEY_SIZE }
        if (key == null) {
            AdmissionTrace.refused(reason = LinkFollowUp.WrongLogin.name)
            return AdmissionSubmitOutcome.WrongLogin
        }
        val publicKey = signingPublicKey(key) ?: return AdmissionSubmitOutcome.SigningFailed
        AdmissionTrace.link(followUp = LinkFollowUp.Submit.name)
        val signature = try {
            signFlush(key, link, publicKey)
        } catch (error: IllegalArgumentException) {
            Napier.w(error) { "Reset was not signed." }
            return AdmissionSubmitOutcome.SigningFailed
        }
        return when (val result = flushPoster.postFlush(link, publicKey, signature)) {
            AdmissionPostResult.Submitted -> AdmissionSubmitOutcome.Submitted
            AdmissionPostResult.BadSignature -> AdmissionSubmitOutcome.BadSignature
            AdmissionPostResult.Malformed -> AdmissionSubmitOutcome.Malformed
            is AdmissionPostResult.DeliveryFailed -> AdmissionSubmitOutcome.DeliveryFailed(
                failure = result.failure,
                httpCode = result.httpCode,
            )
        }
    }

    private fun signedPost(
        link: SignInLink,
        key: ByteArray,
        objectBytes: ByteArray,
    ): AdmissionSubmitOutcome {
        val signature = try {
            signLink(key, link, sha256(objectBytes))
        } catch (error: IllegalArgumentException) {
            Napier.w(error) { "Sign-in was not signed." }
            return AdmissionSubmitOutcome.SigningFailed
        }
        return when (val result = poster.post(link = link, objectBytes = objectBytes, signatureHex = signature)) {
            AdmissionPostResult.Submitted -> AdmissionSubmitOutcome.Submitted
            AdmissionPostResult.BadSignature -> AdmissionSubmitOutcome.BadSignature
            AdmissionPostResult.Malformed -> AdmissionSubmitOutcome.Malformed
            is AdmissionPostResult.DeliveryFailed -> AdmissionSubmitOutcome.DeliveryFailed(
                failure = result.failure,
                httpCode = result.httpCode,
            )
        }
    }
}

private fun LinkPlan.toLocalStop(): AdmissionSubmitOutcome =
    when (followUp) {
        LinkFollowUp.KeyMismatch -> AdmissionSubmitOutcome.KeyMismatch(
            loginPrefix = keys?.loginPrefix.orEmpty(),
            filePrefix = keys?.filePrefix.orEmpty(),
        )
        LinkFollowUp.ObjectKeyUnreadable -> AdmissionSubmitOutcome.ObjectKeyUnreadable
        LinkFollowUp.NoObject -> AdmissionSubmitOutcome.NoObject
        LinkFollowUp.Submit, LinkFollowUp.WrongLogin -> AdmissionSubmitOutcome.WrongLogin
    }
