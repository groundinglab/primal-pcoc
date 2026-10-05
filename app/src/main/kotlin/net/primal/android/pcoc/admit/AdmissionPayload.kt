package net.primal.android.pcoc.admit

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson

enum class AdmissionDecision {
    Accept,
    Deny,
}

data class AdmissionReport(
    val decision: AdmissionDecision,
    val verifierRan: Boolean?,
)

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

    fun parseDecision(body: String): AdmissionDecision? = parseReport(body)?.decision

    fun parseReport(body: String): AdmissionReport? {
        val obj = jsonObject(body)
        val decision = obj?.let { decisionOf(stringField(it, DECISION)) }
        return if (obj == null || decision == null) {
            null
        } else {
            AdmissionReport(decision = decision, verifierRan = booleanField(obj, VERIFIER_RAN))
        }
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

    private fun booleanField(obj: JsonObject, name: String): Boolean? {
        val primitive = obj[name]?.let { value ->
            runCatching { value.jsonPrimitive }.getOrNull()
        }
        return primitive?.booleanOrNull
    }

    private fun decisionOf(text: String?): AdmissionDecision? =
        when (text) {
            ACCEPT -> AdmissionDecision.Accept
            DENY -> AdmissionDecision.Deny
            else -> null
        }

    private const val ACCEPT = "accept"
    private const val DENY = "deny"
    private const val DECISION = "decision"
    private const val VERIFIER_RAN = "verifier_ran"
    private val SIGNATURE = Regex("""^[0-9a-f]{128}$""")

    @Serializable
    private data class AdmissionRequestBody(
        @SerialName("object_b64") val objectB64: String,
        val signature: String,
    )
}
