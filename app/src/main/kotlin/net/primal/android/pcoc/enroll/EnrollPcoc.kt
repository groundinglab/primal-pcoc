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
    fun enroll(
        objectBytes: ByteArray,
        accountHex: String,
        fileName: String,
        savedAtEpochMs: Long,
    ): EnrollOutcome {
        if (!ACCOUNT_HEX.matches(accountHex)) return EnrollOutcome.NotStored
        store.write(accountHex, objectBytes, displayFileName(fileName), savedAtEpochMs)
        return EnrollOutcome.Stored
    }

    fun clear(accountHex: String) {
        store.delete(accountHex)
    }
}

private val ACCOUNT_HEX = Regex("^[0-9a-fA-F]{64}$")
private val HEX_NAME = Regex("(?i)[0-9a-f]{32,}")

/** A plain file name for the screen. Blank, control characters, and a hex key are not shown. */
fun displayFileName(raw: String?): String {
    val cleaned = raw?.filter { it >= ' ' && it != '\u007f' }?.trim().orEmpty()
    val hidden = cleaned.isEmpty() || HEX_NAME.matches(cleaned)
    return if (hidden) FALLBACK_FILE_NAME else cleaned.take(MAX_FILE_NAME_CHARS)
}

private const val FALLBACK_FILE_NAME = "PCOC file"
private const val MAX_FILE_NAME_CHARS = 120
