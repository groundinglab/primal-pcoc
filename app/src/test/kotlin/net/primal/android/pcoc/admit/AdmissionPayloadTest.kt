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
        AdmissionPayload.parseReport(acceptBody) shouldBe AdmissionReport(
            decision = AdmissionDecision.Accept,
            verifierRan = true,
        )
        AdmissionPayload.parseReport("""{"decision":"deny","verifier_ran":false}""") shouldBe
            AdmissionReport(decision = AdmissionDecision.Deny, verifierRan = false)
        AdmissionPayload.parseReport("""{"decision":"accept"}""") shouldBe
            AdmissionReport(decision = AdmissionDecision.Accept, verifierRan = null)
        AdmissionPayload.parseReport("""{"decision":"deny","verifier_ran":"yes"}""") shouldBe
            AdmissionReport(decision = AdmissionDecision.Deny, verifierRan = null)
        AdmissionPayload.parseDecision("") shouldBe null
        AdmissionPayload.parseDecision("not-json") shouldBe null
        AdmissionPayload.parseDecision("{}") shouldBe null
        AdmissionPayload.parseDecision("""{"decision":"maybe"}""") shouldBe null
        AdmissionPayload.parseDecision("""{"decision":1}""") shouldBe null
        AdmissionPayload.parseDecision("""{"decision":"Accept"}""") shouldBe null
    }

    @Test
    fun `gate text keeps verifier_ran and does not invent a local check sentence`() {
        val ran = """{"decision":"deny","verifier_ran":true,"result":"no","measurements":{"section":15}}"""
        val early = """{"decision":"deny","verifier_ran":false,"measurements":{"n":1}}"""
        val missing = """{"decision":"accept"}"""

        val ranReport = AdmissionPayload.parseReport(ran)!!
        val earlyReport = AdmissionPayload.parseReport(early)!!
        val missingReport = AdmissionPayload.parseReport(missing)!!

        gateResultText(ranReport.decision, ranReport.verifierRan) shouldBe
            "The service said:\ndecision: deny\nverifier_ran: true"
        gateResultText(earlyReport.decision, earlyReport.verifierRan) shouldBe
            "The service said:\ndecision: deny\nverifier_ran: false"
        gateResultText(missingReport.decision, missingReport.verifierRan) shouldBe
            "The service said:\ndecision: accept\nverifier_ran: not in the response"
        listOf(ranReport, earlyReport, missingReport).forEach { report ->
            val text = gateResultText(report.decision, report.verifierRan)
            text.contains("did not run") shouldBe false
            text.contains("The check ran") shouldBe false
        }
    }
}
