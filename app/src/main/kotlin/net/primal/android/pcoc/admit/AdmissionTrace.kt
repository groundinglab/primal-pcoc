package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier

/** Logcat lines for a sign-in. No nsec, no full signature, no full nonce, no response body. */
internal object AdmissionTrace {
    fun link(followUp: String, keys: ObjectKeyReport? = null) {
        Napier.i(tag = TAG) { "admission link followUp=$followUp${keySuffix(keys)}" }
    }

    fun refused(reason: String, keys: ObjectKeyReport? = null) {
        Napier.i(tag = TAG) { "admission refused reason=$reason${keySuffix(keys)}" }
    }

    fun post(
        link: SignInLink,
        objectSha256: ByteArray,
        objectBytes: Int,
        signatureHex: String,
    ) {
        Napier.i(tag = TAG) {
            admissionPostLog(
                link = link,
                objectSha256 = objectSha256,
                objectBytes = objectBytes,
                signatureHex = signatureHex,
            )
        }
    }

    fun http(status: Int) {
        Napier.i(tag = TAG) { "admission http status=$status" }
    }
}

internal fun admissionPostLog(
    link: SignInLink,
    objectSha256: ByteArray,
    objectBytes: Int,
    signatureHex: String,
): String {
    val objectPrefix = fr.acinq.secp256k1.Hex.encode(objectSha256).lowercase().take(TRACE_EDGE)
    return "admission post door=$SIGN_IN_DOOR origin=${link.origin} exp=${link.exp} " +
        "nonce=${link.nonceHex.take(TRACE_EDGE)}… objectSha=$objectPrefix… " +
        "objectBytes=$objectBytes sig=${signatureTrace(signatureHex)}"
}

internal fun admissionKeyLog(keys: ObjectKeyReport): String {
    val account = shownKeyPrefix(keys.accountPrefix)
    val extra = buildString {
        if (account != NO_KEY) append(" account=$account")
        if (keys.readersDisagree) append(" readersDisagree=true scan=${shownKeyPrefix(keys.scanPrefix)}")
    }
    return "login=${shownKeyPrefix(keys.loginPrefix)} file=${shownKeyPrefix(keys.filePrefix)} " +
        "keySource=${keySourceWord(keys.keySource)}$extra"
}

internal fun signatureTrace(signatureHex: String): String {
    val hex = signatureHex.lowercase()
    return if (hex.length < TRACE_EDGE * 2) {
        "len=${hex.length}"
    } else {
        "len=${hex.length} ${hex.take(TRACE_EDGE)}…${hex.takeLast(TRACE_EDGE)}"
    }
}

private fun keySourceWord(source: String): String =
    when (source) {
        "object", "scan", NO_KEY -> source
        else -> NO_KEY
    }

private fun keySuffix(keys: ObjectKeyReport?): String = if (keys == null) "" else " ${admissionKeyLog(keys)}"

private const val NO_KEY = "none"
private const val TAG = "Admission"
private const val TRACE_EDGE = 8
