package net.primal.android.settings.groundinglab

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson

/**
 * Locked sign-in body. Fields are only `object_b64` and `signature`.
 * Height, the QR hash, `id`, and `challenge_id` are not request fields.
 */
object SignInPayload {

    private val json: Json = CommonJson

    fun encode(objectBytes: ByteArray, signatureHex: String): String =
        json.encodeToString(
            SignInRequest.serializer(),
            SignInRequest(
                objectB64 = Base64.getEncoder().encodeToString(objectBytes),
                signature = signatureHex,
            ),
        )

    fun parseDecision(body: String): String? {
        if (body.isBlank()) return null
        val decision = runCatching {
            json.decodeFromString(SignInResponse.serializer(), body).decision
        }.getOrNull()
        return decision?.takeIf { it == ACCEPT || it == DENY }
    }

    const val ACCEPT = "accept"
    const val DENY = "deny"

    @Serializable
    private data class SignInRequest(
        @SerialName("object_b64") val objectB64: String,
        val signature: String,
    )

    @Serializable
    private data class SignInResponse(
        val decision: String? = null,
    )
}
