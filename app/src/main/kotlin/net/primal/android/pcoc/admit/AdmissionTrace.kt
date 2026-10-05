package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson

internal data class AdmissionTarget(
    val host: String,
    val path: String,
)

internal data class AdmissionBodyFields(
    val hasObjectB64: Boolean,
    val hasSignature: Boolean,
)

internal object AdmissionTrace {
    fun scan(
        pubkeyMatch: Boolean?,
        hBytes: Int,
        target: AdmissionTarget,
        followUp: String,
        keys: ObjectKeyReport? = null,
    ) {
        Napier.i(tag = TAG) {
            "admission scan pubkeyMatch=${matchWord(pubkeyMatch)} hBytes=$hBytes " +
                "host=${target.host} path=${target.path} followUp=$followUp${keySuffix(keys)}"
        }
    }

    fun sign(
        pubkeyMatch: Boolean,
        hBytes: Int,
        target: AdmissionTarget,
        keys: ObjectKeyReport? = null,
    ) {
        Napier.i(tag = TAG) {
            "admission sign pubkeyMatch=${matchWord(pubkeyMatch)} hBytes=$hBytes " +
                "host=${target.host} path=${target.path}${keySuffix(keys)}"
        }
    }

    fun post(
        target: AdmissionTarget,
        hBytes: Int,
        hasObjectB64: Boolean,
        hasSignature: Boolean,
        signatureHex: String,
    ) {
        Napier.i(tag = TAG) {
            "admission post host=${target.host} path=${target.path} hBytes=$hBytes " +
                "object_b64=$hasObjectB64 signature=$hasSignature sig=${signatureTrace(signatureHex)}"
        }
    }

    fun http(status: Int, body: String) {
        Napier.i(tag = TAG) { admissionHttpLog(status = status, body = body) }
    }

    fun refused(
        reason: String,
        pubkeyMatch: Boolean?,
        hBytes: Int?,
        target: AdmissionTarget?,
        keys: ObjectKeyReport? = null,
    ) {
        val where = if (target == null) {
            "host=none path=none"
        } else {
            "host=${target.host} path=${target.path}"
        }
        Napier.i(tag = TAG) {
            "admission refused reason=$reason pubkeyMatch=${matchWord(pubkeyMatch)} " +
                "hBytes=${hBytes ?: 0} $where${keySuffix(keys)}"
        }
    }
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

private fun keySourceWord(source: String): String =
    when (source) {
        "object", "scan", NO_KEY -> source
        else -> NO_KEY
    }

private const val NO_KEY = "none"

private fun keySuffix(keys: ObjectKeyReport?): String = if (keys == null) "" else " ${admissionKeyLog(keys)}"

internal fun admissionTarget(exactUrl: String): AdmissionTarget {
    val uri = try {
        URI(exactUrl)
    } catch (error: java.net.URISyntaxException) {
        Napier.w(error) { "Service admission URL was not parsed." }
        return AdmissionTarget(host = UNPARSED, path = "/")
    }
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    return AdmissionTarget(host = uri.host ?: UNPARSED, path = path)
}

internal fun admissionBodyFields(json: String): AdmissionBodyFields {
    val element = runCatching { CommonJson.parseToJsonElement(json) }.getOrNull()
    val obj = element?.let { value -> runCatching { value.jsonObject }.getOrNull() }
    return AdmissionBodyFields(
        hasObjectB64 = !jsonString(obj, OBJECT_B64).isNullOrEmpty(),
        hasSignature = !jsonString(obj, SIGNATURE).isNullOrEmpty(),
    )
}

internal fun signatureTrace(signatureHex: String): String {
    val hex = signatureHex.lowercase()
    return if (hex.length < TRACE_EDGE * 2) {
        "len=${hex.length}"
    } else {
        "len=${hex.length} ${hex.take(TRACE_EDGE)}…${hex.takeLast(TRACE_EDGE)}"
    }
}

internal fun redactAdmissionLog(text: String): String {
    val withoutNsec = NSEC.replace(text, "nsec1…")
    return SIGNATURE_HEX.replace(withoutNsec) { match ->
        val value = match.value
        value.take(TRACE_EDGE) + "…" + value.takeLast(TRACE_EDGE)
    }
}

internal fun admissionHttpLog(status: Int, body: String): String {
    val redacted = redactAdmissionLog(body)
    val shown = if (redacted.length <= BODY_CAP) {
        redacted
    } else {
        redacted.take(BODY_CAP) + "…truncated"
    }
    return "admission http status=$status body=$shown"
}

private fun matchWord(match: Boolean?): String =
    when (match) {
        true -> "true"
        false -> "false"
        null -> "unknown"
    }

private fun jsonString(obj: JsonObject?, name: String): String? {
    if (obj == null) return null
    val primitive = obj[name]?.let { value ->
        runCatching { value.jsonPrimitive }.getOrNull()
    }
    return primitive?.takeIf(JsonPrimitive::isString)?.content
}

private const val TAG = "Admission"
private const val UNPARSED = "unparsed"
private const val OBJECT_B64 = "object_b64"
private const val SIGNATURE = "signature"
private const val TRACE_EDGE = 8
private const val BODY_CAP = 16_000
private val NSEC = Regex("""nsec1[0-9a-z]+""", RegexOption.IGNORE_CASE)
private val SIGNATURE_HEX = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{128}(?![0-9a-fA-F])""")
