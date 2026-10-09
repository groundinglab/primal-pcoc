package net.primal.android.pcoc.admit

import java.security.MessageDigest

internal enum class ObjectKeyCheck {
    Match,
    Mismatch,
    Unreadable,
}

internal enum class LinkFollowUp {
    Submit,
    WrongLogin,
    KeyMismatch,
    ObjectKeyUnreadable,
    ObjectNotHeld,
    NoObject,
}

internal data class LinkPlan(
    val followUp: LinkFollowUp,
    val keys: ObjectKeyReport? = null,
)

/**
 * Local stops before anything is signed. The phone signs only when it holds the bytes whose SHA-256
 * is the link's `object`, the object's public key is this login, and the login holds an nsec.
 * Every other link goes straight to sign and POST: showing the origin is the only confirmation.
 */
internal fun planLink(
    link: SignInLink,
    canSign: Boolean,
    accountHex: String,
    objectBytes: ByteArray?,
    signingKey: ByteArray? = null,
): LinkPlan =
    when {
        objectBytes == null -> LinkPlan(followUp = LinkFollowUp.NoObject)
        !MessageDigest.isEqual(sha256(objectBytes), link.objectSha256()) ->
            LinkPlan(followUp = LinkFollowUp.ObjectNotHeld)
        else -> {
            val keys = objectKeyReport(accountHex = accountHex, signingKey = signingKey, objectBytes = objectBytes)
            val followUp = when (keys.check) {
                ObjectKeyCheck.Unreadable -> LinkFollowUp.ObjectKeyUnreadable
                ObjectKeyCheck.Mismatch -> LinkFollowUp.KeyMismatch
                ObjectKeyCheck.Match -> if (canSign) LinkFollowUp.Submit else LinkFollowUp.WrongLogin
            }
            LinkPlan(followUp = followUp, keys = keys)
        }
    }
