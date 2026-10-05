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
)

/**
 * A good scan opens Confirm only when the saved object's public key is the logged-in account.
 * A mismatch or a missing key never reaches Confirm and never becomes a service denial.
 */
internal fun planReadyScan(
    canSign: Boolean,
    accountHex: String,
    objectBytes: ByteArray?,
): ReadyScanPlan {
    if (objectBytes == null) {
        return ReadyScanPlan(followUp = ReadyScanFollowUp.NoObject, pubkeyMatch = null)
    }
    val check = checkObjectKey(accountHex = accountHex, objectBytes = objectBytes)
    val followUp = when (check) {
        ObjectKeyCheck.Unreadable -> ReadyScanFollowUp.ObjectKeyUnreadable
        ObjectKeyCheck.Mismatch -> ReadyScanFollowUp.KeyMismatch
        ObjectKeyCheck.Match -> if (canSign) ReadyScanFollowUp.Confirm else ReadyScanFollowUp.WrongLogin
    }
    val pubkeyMatch = check == ObjectKeyCheck.Match
    return ReadyScanPlan(followUp = followUp, pubkeyMatch = pubkeyMatch)
}
