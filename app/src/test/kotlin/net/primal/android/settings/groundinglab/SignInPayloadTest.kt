package net.primal.android.settings.groundinglab

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class SignInPayloadTest {

    @Test
    fun encode_isStandardBase64AndOnlyTwoFields() {
        val objectBytes = byteArrayOf(0xfb.toByte(), 0xef.toByte(), 0xff.toByte())
        val signature = "cd".repeat(64)
        val tipHash = "ab".repeat(32)
        val body = SignInPayload.encode(objectBytes = objectBytes, signatureHex = signature)

        val json = Json.parseToJsonElement(body).jsonObject
        json.keys shouldBe setOf("object_b64", "signature")
        json.getValue("object_b64").jsonPrimitive.content shouldBe
            Base64.getEncoder().encodeToString(objectBytes)
        json.getValue("signature").jsonPrimitive.content shouldBe signature
        body.shouldNotContain(tipHash)
        body.shouldNotContain("height")
        body.shouldNotContain("challenge_id")
        body.shouldNotContain("\"id\"")
        (body.contains('+') || body.contains('/')) shouldBe true
        body.shouldNotContain("\n")
    }

    @Test
    fun parseDecision_returnsAcceptOrDenyOnly() {
        SignInPayload.parseDecision("""{"decision":"accept","checked":true}""") shouldBe "accept"
        SignInPayload.parseDecision("""{"decision":"deny"}""") shouldBe "deny"
        SignInPayload.parseDecision("") shouldBe null
        SignInPayload.parseDecision("{}") shouldBe null
        SignInPayload.parseDecision("""{"decision":"maybe"}""") shouldBe null
        SignInPayload.parseDecision("not-json") shouldBe null
    }
}
