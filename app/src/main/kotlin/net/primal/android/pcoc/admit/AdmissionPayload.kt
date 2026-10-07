package net.primal.android.pcoc.admit

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson

/**
 * Phone POST body. Fields are only `object_b64` and `signature`.
 * No hash, height, id, challenge_id, sid, or nsec.
 *
 * The phone does not read a policy verdict from the response.
 * [isBadSignature] is the only body it treats as an error name.
 * `decision`, `verifier_ran`, provisional, checking, and revoke are ignored.
 */
object AdmissionPayload {
    fun encode(objectBytes: ByteArray, signatureHex: String): String {
        val signature = signatureHex.lowercase()
        require(SIGNATURE.matches(signature))
        return CommonJson.encodeToString(
            AdmissionRequestBody.serializer(),
            AdmissionRequestBody(
                objectB64 = Base64.getEncoder().encodeToString(objectBytes),
                signature = signature,
            ),
        )
    }

    /**
     * True only for the error name `bad_signature`.
     * A `decision` of deny, or `verifier_ran` false, is not this error.
     */
    fun isBadSignature(body: String): Boolean {
        val obj = jsonObject(body)
        val error = obj?.get(ERROR)
        val nested = error?.let { value -> runCatching { value.jsonObject }.getOrNull() }
        val errorText = error?.let { value ->
            runCatching { value.jsonPrimitive }.getOrNull()?.takeIf(JsonPrimitive::isString)?.content
        }
        return obj != null && (
            stringField(obj, REASON) == BAD_SIGNATURE ||
                stringField(obj, CODE) == BAD_SIGNATURE ||
                errorText == BAD_SIGNATURE ||
                nested?.let { stringField(it, CODE) } == BAD_SIGNATURE
            )
    }

    private fun jsonObject(body: String) =
        if (body.isBlank()) {
            null
        } else {
            val element = runCatching { CommonJson.parseToJsonElement(body) }.getOrNull()
            element?.let { runCatching { it.jsonObject }.getOrNull() }
        }

    private fun stringField(obj: JsonObject, name: String): String? {
        val primitive = obj[name]?.let { value ->
            runCatching { value.jsonPrimitive }.getOrNull()
        }
        return primitive?.takeIf(JsonPrimitive::isString)?.content
    }

    private const val BAD_SIGNATURE = "bad_signature"
    private const val ERROR = "error"
    private const val CODE = "code"
    private const val REASON = "reason"
    private val SIGNATURE = Regex("""^[0-9a-f]{128}$""")

    @Serializable
    private data class AdmissionRequestBody(
        @SerialName("object_b64") val objectB64: String,
        val signature: String,
    )
}
