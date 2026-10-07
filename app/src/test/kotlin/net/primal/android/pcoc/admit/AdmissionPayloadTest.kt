package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class AdmissionPayloadTest {

    @Test
    fun `body has only object_b64 and signature`() {
        val objectBytes = byteArrayOf(0xfb.toByte(), 0x10, 0xff.toByte())
        val signature = "cd".repeat(64)
        val json = AdmissionPayload.encode(objectBytes = objectBytes, signatureHex = signature)
        val obj = Json.parseToJsonElement(json).jsonObject

        obj.keys shouldBe setOf("object_b64", "signature")
        obj.getValue("signature").toString().trim('"') shouldBe signature
        Base64.getDecoder().decode(obj.getValue("object_b64").toString().trim('"'))
            .contentEquals(objectBytes) shouldBe true
        json.contains("challenge_id") shouldBe false
        json.contains("sid") shouldBe false
        json.contains("nsec") shouldBe false
        json.contains("ab".repeat(32)) shouldBe false
    }

    @Test
    fun `bad signature is only the named error`() {
        AdmissionPayload.isBadSignature(
            """{"error":{"code":"bad_signature","message":"the signature does not verify"}}""",
        ) shouldBe true
        AdmissionPayload.isBadSignature("""{"error":"bad_signature"}""") shouldBe true
        AdmissionPayload.isBadSignature("""{"reason":"bad_signature"}""") shouldBe true
        AdmissionPayload.isBadSignature("""{"code":"bad_signature"}""") shouldBe true
        AdmissionPayload.isBadSignature(
            """{"decision":"deny","error":{"code":"bad_signature"}}""",
        ) shouldBe true
    }

    @Test
    fun `policy words are not a bad signature`() {
        listOf(
            "",
            "not-json",
            "{}",
            """{"decision":"accept","verifier_ran":true,"result":"ok"}""",
            """{"decision":"deny","verifier_ran":false}""",
            """{"decision":"deny","verifier_ran":true}""",
            """{"decision":"provisional","phase":"fast","seq":1}""",
            """{"decision":"checking"}""",
            """{"decision":"revoke"}""",
            """{"error":{"code":"bad_gateway","message":"the admission gate did not answer"}}""",
            """{"error":{"code":"bad_request","message":"h is not 64 lowercase hex"}}""",
            """{"error":{"message":"bad_signature"}}""",
        ).forEach { body ->
            AdmissionPayload.isBadSignature(body) shouldBe false
        }
    }
}
