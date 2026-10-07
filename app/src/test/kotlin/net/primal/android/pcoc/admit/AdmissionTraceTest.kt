package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import org.junit.Test

class AdmissionTraceTest {

    @Test
    fun `live sign-in target is host and path only`() {
        val hash = "01" + "ab".repeat(30) + "ff"
        val target = admissionTarget("https://pcoc.app/sign-in/?h=$hash")
        target.host shouldBe "pcoc.app"
        target.path shouldBe "/sign-in/"
        val line = "${target.host}${target.path}"
        line.contains(hash) shouldBe false
    }

    @Test
    fun `encoded body reports both fields`() {
        val json = AdmissionPayload.encode(byteArrayOf(1, 2, 3), "ab".repeat(64))
        val fields = admissionBodyFields(json)
        fields.hasObjectB64 shouldBe true
        fields.hasSignature shouldBe true
        signatureTrace("ab".repeat(64)) shouldBe "len=128 ${"ab".repeat(4)}…${"ab".repeat(4)}"
    }

    @Test
    fun `response log keeps measurements and shortens a signature`() {
        val signature = "01234567" + "aa".repeat(56) + "89abcdef"
        val hash = "cd".repeat(32)
        val body = """{"decision":"deny","verifier_ran":true,"measurements":{"section":15,"tip":"$hash"},""" +
            """"signature":"$signature","leak":"nsec1qqqqqqqqqqqqqqqqqqqq"}"""
        val log = admissionHttpLog(status = 200, body = body)
        log shouldContain "status=200"
        log shouldContain "\"verifier_ran\":true"
        log shouldContain "measurements"
        log shouldContain hash
        log.contains(signature) shouldBe false
        log shouldContain "01234567…89abcdef"
        log.contains("nsec1qqq") shouldBe false
        log.contains("did not run") shouldBe false
    }

    @Test
    fun `local admission copy does not reuse the gate check sentence`() {
        val xml = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
        xml.contains("The check did not run") shouldBe false
        xml.contains("The check ran") shouldBe false
        xml.contains("This saved PCOC is for a different login. It was not sent.") shouldBe true
        xml.contains("Sending…") shouldBe true
        xml.contains("name=\"pcoc_admit_delivered\"") shouldBe true
        xml.contains("name=\"pcoc_admit_failed\">The sign-in was not delivered. This is not a refusal.") shouldBe true
        xml.contains("Request failed. This is not a denial.") shouldBe false
        xml.contains("The service said:") shouldBe false
        val detail = Regex("""name="pcoc_admit_key_mismatch_detail">([^<]+)</string>""")
            .find(xml)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        detail shouldBe "This login starts with %1\$s. The saved file starts with %2\$s."
        detail.contains("pubkey") shouldBe false
        detail.contains("nsec") shouldBe false
        detail.contains("hex") shouldBe false
        detail.contains("cbor") shouldBe false
    }
}
