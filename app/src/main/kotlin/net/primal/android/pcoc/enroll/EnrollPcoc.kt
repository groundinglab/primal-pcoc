package net.primal.android.pcoc.enroll

/**
 * Enroll stores the picked file for the signed-in account.
 * It does not read a key from the file, and it does not decide admission.
 */
sealed class EnrollOutcome {
    data object Stored : EnrollOutcome()

    data object NotStored : EnrollOutcome()
}

class EnrollPcoc(
    private val store: EnrolledPcocStore,
) {
    fun enroll(objectBytes: ByteArray, accountHex: String): EnrollOutcome {
        if (!ACCOUNT_HEX.matches(accountHex)) return EnrollOutcome.NotStored
        store.write(accountHex, objectBytes)
        return EnrollOutcome.Stored
    }

    fun clear(accountHex: String) {
        store.delete(accountHex)
    }
}

private val ACCOUNT_HEX = Regex("^[0-9a-fA-F]{64}$")
