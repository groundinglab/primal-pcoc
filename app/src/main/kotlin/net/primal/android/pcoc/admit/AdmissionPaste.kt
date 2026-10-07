package net.primal.android.pcoc.admit

/**
 * Website Copy-code paste. Trim whitespace only.
 * The pinned string is `https://pcoc.app/sign-in/?h=` plus 64 lowercase hex.
 * A match is the scan input for that same URL. Anything else is one rejection.
 * A bare 64-hex clipboard is not this cut.
 */
sealed class AdmissionPasteParse {
    data class Ready(val exactUrl: String) : AdmissionPasteParse()

    data object Rejected : AdmissionPasteParse()
}

object AdmissionPaste {
    fun parse(raw: String): AdmissionPasteParse {
        val exact = raw.trim()
        return if (PINNED.matches(exact)) {
            AdmissionPasteParse.Ready(exactUrl = exact)
        } else {
            AdmissionPasteParse.Rejected
        }
    }
}

private val PINNED = Regex("""^https://pcoc\.app/sign-in/\?h=[0-9a-f]{64}$""")
