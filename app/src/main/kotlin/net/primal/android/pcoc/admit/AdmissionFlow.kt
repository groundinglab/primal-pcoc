package net.primal.android.pcoc.admit

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
    NoObject,
}

internal data class LinkPlan(
    val followUp: LinkFollowUp,
    val keys: ObjectKeyReport? = null,
)

/**
 * Local stops before anything is signed. The phone signs the hike object saved for this login.
 * The link does not name that object. The object's public key must be this login, and the login
 * must hold an nsec. Showing the origin is the only confirmation.
 */
internal fun planLink(
    link: SignInLink,
    canSign: Boolean,
    accountHex: String,
    objectBytes: ByteArray?,
    signingKey: ByteArray? = null,
): LinkPlan {
    check(link.nonce().size == 32)
    return when {
        objectBytes == null -> LinkPlan(followUp = LinkFollowUp.NoObject)
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
}
