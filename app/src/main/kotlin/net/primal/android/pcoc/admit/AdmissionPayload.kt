package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
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
        require(link.kind == GateLinkKind.SignIn)
        val signature = signatureHex.lowercase()
        require(SIGNATURE.matches(signature))
        return CommonJson.encodeToString(
            SignInRequestBody.serializer(),
            SignInRequestBody(
                origin = link.origin,
                nonce = link.nonceHex,
                exp = link.exp,
                objectSha256 = Hex.encode(sha256(objectBytes)),
                objectB64 = Base64.getEncoder().encodeToString(objectBytes),
                signature = signature,
            ),
        )
    }

    /**
     * Flush POST body: `origin`, `nonce`, `exp`, `public_key`, `signature`.
     * No object and no object_b64. `public_key` is the login's x-only key, lowercase hex.
     */
    fun encodeFlush(
        link: SignInLink,
        publicKey: ByteArray,
        signatureHex: String,
    ): String {
        require(link.kind == GateLinkKind.Flush)
        require(publicKey.size == PUBLIC_KEY_BYTES)
        val signature = signatureHex.lowercase()
        require(SIGNATURE.matches(signature))
        val key = Hex.encode(publicKey).lowercase()
        require(PUBLIC_KEY_HEX.matches(key))
        return CommonJson.encodeToString(
            FlushRequestBody.serializer(),
            FlushRequestBody(
                origin = link.origin,
                nonce = link.nonceHex,
                exp = link.exp,
                publicKey = key,
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
    private const val PUBLIC_KEY_BYTES = 32
    private val SIGNATURE = Regex("""^[0-9a-f]{128}$""")
    private val PUBLIC_KEY_HEX = Regex("""^[0-9a-f]{64}$""")

    @Serializable
    private data class SignInRequestBody(
        val origin: String,
        val nonce: String,
        val exp: ULong,
        @SerialName("object") val objectSha256: String,
        @SerialName("object_b64") val objectB64: String,
        val signature: String,
    )

    @Serializable
    private data class FlushRequestBody(
        val origin: String,
        val nonce: String,
        val exp: ULong,
        @SerialName("public_key") val publicKey: String,
        val signature: String,
    )
}
