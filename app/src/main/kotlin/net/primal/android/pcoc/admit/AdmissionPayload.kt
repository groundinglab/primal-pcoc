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
 * SPEC §15.1 POST body: `origin`, `nonce`, `exp`, `object`, `object_b64`, `signature`.
 * Standard base64 with padding, lowercase hex. No nsec, no header hash, no session id.
 */
object AdmissionPayload {
    fun encode(
        link: SignInLink,
        objectBytes: ByteArray,
        signatureHex: String,
    ): String {
        val signature = signatureHex.lowercase()
        require(SIGNATURE.matches(signature))
        return CommonJson.encodeToString(
            SignInRequestBody.serializer(),
            SignInRequestBody(
                origin = link.origin,
                nonce = link.nonceHex,
                exp = link.exp,
                objectSha256 = link.objectHex,
                objectB64 = Base64.getEncoder().encodeToString(objectBytes),
                signature = signature,
            ),
        )
    }

    /** `error.code` of a JSON body, or null. The phone reads nothing else from a response. */
    fun errorCode(body: String): String? {
        val error = jsonObject(body)?.get(ERROR)?.let { runCatching { it.jsonObject }.getOrNull() }
        return error?.let { stringField(it, CODE) }
    }

    private fun jsonObject(body: String): JsonObject? =
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

    private const val ERROR = "error"
    private const val CODE = "code"
    private val SIGNATURE = Regex("""^[0-9a-f]{128}$""")

    @Serializable
    private data class SignInRequestBody(
        val origin: String,
        val nonce: String,
        val exp: ULong,
        @SerialName("object") val objectSha256: String,
        @SerialName("object_b64") val objectB64: String,
        val signature: String,
    )
}
