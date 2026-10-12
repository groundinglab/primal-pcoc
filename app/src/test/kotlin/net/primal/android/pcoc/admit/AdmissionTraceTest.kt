package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import org.junit.Test

class AdmissionTraceTest {

    @Test
    fun `post line names the door and origin and shortens the nonce, object, and signature`() {
        val link = linkFor(nonceHex = "fe".repeat(32))
        val signature = "01234567" + "aa".repeat(56) + "89abcdef"
        val objectSha = sha256(byteArrayOf(1, 2, 3))
        val line = admissionPostLog(link = link, objectSha256 = objectSha, objectBytes = 3, signatureHex = signature)

        line shouldContain "door=https://admission.groundinglab.io/v1/sign-in"
        line shouldContain "origin=https://groundinglab.io"
        line shouldContain "exp=1893456000"
        line shouldContain "objectBytes=3"
        line shouldContain "sig=len=128 01234567…89abcdef"
        line.contains(signature) shouldBe false
        line.contains(link.nonceHex) shouldBe false
        line.contains(fr.acinq.secp256k1.Hex.encode(objectSha)) shouldBe false
        line.contains("nsec") shouldBe false
    }

    @Test
    fun `local admission copy keeps the key mismatch detail plain`() {
        val xml = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
        xml.contains("This saved PCOC is for a different login. It was not sent.") shouldBe true
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
