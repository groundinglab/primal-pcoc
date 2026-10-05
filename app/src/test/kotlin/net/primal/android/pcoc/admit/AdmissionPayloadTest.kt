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
    fun `decision is only accept or deny and extra fields are ignored`() {
        val acceptBody = """{"decision":"accept","verifier_ran":true,"result":"ok","measurements":{}}"""
        AdmissionPayload.parseDecision(acceptBody) shouldBe AdmissionDecision.Accept
        AdmissionPayload.parseDecision("""{"decision":"deny","verifier_ran":false}""") shouldBe
            AdmissionDecision.Deny
        AdmissionPayload.parseDecision("") shouldBe null
        AdmissionPayload.parseDecision("not-json") shouldBe null
        AdmissionPayload.parseDecision("{}") shouldBe null
        AdmissionPayload.parseDecision("""{"decision":"maybe"}""") shouldBe null
        AdmissionPayload.parseDecision("""{"decision":1}""") shouldBe null
        AdmissionPayload.parseDecision("""{"decision":"Accept"}""") shouldBe null
    }
}
