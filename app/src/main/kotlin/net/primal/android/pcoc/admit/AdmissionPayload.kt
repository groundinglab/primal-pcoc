package net.primal.android.pcoc.admit

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson

enum class AdmissionDecision {
    Accept,
    Deny,
}

/**
 * Phone POST body. Fields are only `object_b64` and `signature`.
 * No hash, height, id, challenge_id, sid, or nsec.
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

    fun parseDecision(body: String): AdmissionDecision? {
        val text = decisionText(body) ?: return null
        return when (text) {
            ACCEPT -> AdmissionDecision.Accept
            DENY -> AdmissionDecision.Deny
            else -> null
        }
    }

    private fun decisionText(body: String): String? {
        if (body.isBlank()) return null
        val element = runCatching { CommonJson.parseToJsonElement(body) }.getOrNull()
        val obj = element?.let { runCatching { it.jsonObject }.getOrNull() }
        val primitive = obj?.get(DECISION)?.let { value ->
            runCatching { value.jsonPrimitive }.getOrNull()
        }
        return primitive?.takeIf(JsonPrimitive::isString)?.content
    }

    private const val ACCEPT = "accept"
    private const val DENY = "deny"
    private const val DECISION = "decision"
    private val SIGNATURE = Regex("""^[0-9a-f]{128}$""")

    @Serializable
    private data class AdmissionRequestBody(
        @SerialName("object_b64") val objectB64: String,
        val signature: String,
    )
}
