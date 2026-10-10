package net.primal.android.pcoc.admit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.serialization.CommonJson
import org.junit.Test

class AdmissionPayloadTest {

    @Test
    fun `body is the six fields with padded standard base64 and lowercase hex`() {
        val objectBytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0x3e)
        val link = linkFor()
        val json = AdmissionPayload.encode(link = link, objectBytes = objectBytes, signatureHex = "AB".repeat(64))
        val fields = CommonJson.parseToJsonElement(json).jsonObject

        fields.keys shouldBe setOf("origin", "nonce", "exp", "object", "object_b64", "signature")
        fields.getValue("origin").jsonPrimitive.content shouldBe "https://pcoc.app"
        fields.getValue("nonce").jsonPrimitive.content shouldBe link.nonceHex
        fields.getValue("exp").jsonPrimitive.isString shouldBe false
        fields.getValue("exp").jsonPrimitive.content shouldBe "1893456000"
        fields.getValue("object").jsonPrimitive.content shouldBe fr.acinq.secp256k1.Hex.encode(sha256(objectBytes))
        fields.getValue("object_b64").jsonPrimitive.content shouldBe "+/8+"
        fields.getValue("signature").jsonPrimitive.content shouldBe "ab".repeat(64)

        val padded = AdmissionPayload.encode(linkFor(), byteArrayOf(1), "cd".repeat(64))
        CommonJson.parseToJsonElement(padded).jsonObject.getValue("object_b64").jsonPrimitive.content shouldBe "AQ=="
    }

    @Test
    fun `a signature that is not 64 bytes of hex is refused`() {
        val link = linkFor()
        shouldThrow<IllegalArgumentException> { AdmissionPayload.encode(link, byteArrayOf(1), "zz".repeat(64)) }
        shouldThrow<IllegalArgumentException> { AdmissionPayload.encode(link, byteArrayOf(1), "ab".repeat(63)) }
    }

    @Test
    fun `error code is read from error dot code only`() {
        AdmissionPayload.errorCode("""{"error":{"code":"bad_signature"}}""") shouldBe "bad_signature"
        AdmissionPayload.errorCode("""{"error":{"code":"malformed"}}""") shouldBe "malformed"
        AdmissionPayload.errorCode("""{"error":{"code":1}}""") shouldBe null
        AdmissionPayload.errorCode("""{"error":"malformed"}""") shouldBe null
        AdmissionPayload.errorCode("""{"code":"malformed"}""") shouldBe null
        AdmissionPayload.errorCode("""{"decision":"deny"}""") shouldBe null
        AdmissionPayload.errorCode("") shouldBe null
        AdmissionPayload.errorCode("[]") shouldBe null
        AdmissionPayload.errorCode("not-json") shouldBe null
    }
}
