package net.primal.android.pcoc.admit

internal enum class ObjectKeyCheck {
    Match,
    Mismatch,
    Unreadable,
}

internal enum class ReadyScanFollowUp {
    Confirm,
    WrongLogin,
    KeyMismatch,
    ObjectKeyUnreadable,
    NoObject,
}

internal data class ReadyScanPlan(
    val followUp: ReadyScanFollowUp,
    val pubkeyMatch: Boolean?,
    val keys: ObjectKeyReport? = null,
)

/**
 * A good scan opens Confirm when the saved object's public key is the logged-in account
 * or the key that will sign. A mismatch or a missing key never reaches Confirm.
 */
internal fun planReadyScan(
    canSign: Boolean,
    accountHex: String,
    objectBytes: ByteArray?,
    signingKey: ByteArray? = null,
): ReadyScanPlan {
    if (objectBytes == null) {
        return ReadyScanPlan(followUp = ReadyScanFollowUp.NoObject, pubkeyMatch = null)
    }
    val keys = objectKeyReport(accountHex = accountHex, signingKey = signingKey, objectBytes = objectBytes)
    val followUp = when (keys.check) {
        ObjectKeyCheck.Unreadable -> ReadyScanFollowUp.ObjectKeyUnreadable
        ObjectKeyCheck.Mismatch -> ReadyScanFollowUp.KeyMismatch
        ObjectKeyCheck.Match -> if (canSign) ReadyScanFollowUp.Confirm else ReadyScanFollowUp.WrongLogin
    }
    return ReadyScanPlan(
        followUp = followUp,
        pubkeyMatch = keys.check == ObjectKeyCheck.Match,
        keys = keys,
    )
}
