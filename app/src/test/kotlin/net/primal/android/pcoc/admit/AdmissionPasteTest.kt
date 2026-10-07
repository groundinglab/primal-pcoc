package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import org.junit.Test

class AdmissionPasteTest {

    private val hash = "01" + "ab".repeat(30) + "ff"
    private val pinned = "https://pcoc.app/sign-in/?h=$hash"

    @Test
    fun `pinned paste is the scan url and the same h`() {
        val pasted = AdmissionPaste.parse("\n  $pinned \t").shouldBeInstanceOf<AdmissionPasteParse.Ready>()
        pasted.exactUrl shouldBe pinned

        val fromPaste = AdmissionQr.parse(pasted.exactUrl).shouldBeInstanceOf<AdmissionQrParse.Ready>()
        val fromScan = AdmissionQr.parse(pinned).shouldBeInstanceOf<AdmissionQrParse.Ready>()
        fromPaste.exactUrl shouldBe fromScan.exactUrl
        fromPaste.hashHex shouldBe fromScan.hashHex
        fromPaste.hashHex shouldBe hash
        fromPaste.hashBytes.contentEquals(fromScan.hashBytes) shouldBe true
        fromPaste.hashBytes.contentEquals(Hex.decode(hash)) shouldBe true
    }

    @Test
    fun `nbsp around the pinned url trims to the scan url`() {
        val pasted = AdmissionPaste.parse("\u00A0$pinned\r\n").shouldBeInstanceOf<AdmissionPasteParse.Ready>()
        pasted.exactUrl shouldBe pinned
    }

    @Test
    fun `garbage paste is rejected and does not throw`() {
        val junk = listOf(
            "",
            "   ",
            "\n",
            "hello",
            "https://pcoc.app/sign-in/?h=${hash.uppercase()}",
            "https://pcoc.app/sign-in/?h=$hash&x=1",
            "https://www.pcoc.app/sign-in/?h=$hash",
            "https://pcoc.app/sign-in?h=$hash",
            "http://pcoc.app/sign-in/?h=$hash",
            "https://admission.pcoc.app/v1/sign-in?h=$hash",
            "https://pcoc.app:443/sign-in/?h=$hash",
            "HTTPS://pcoc.app/sign-in/?h=$hash",
            "https://evil.example/sign-in/?h=$hash",
            "https://pcoc.app/sign-in/?h=$hash#frag",
            "https://pcoc.app/sign-in/?h=$hash extra",
            "note https://pcoc.app/sign-in/?h=$hash",
            "0x$hash",
            hash,
            hash.uppercase(),
            hash.drop(1),
            hash + "a",
            "\u0000",
            "https://",
            "a".repeat(10_000),
        )
        junk.forEach { sample ->
            AdmissionPaste.parse(sample) shouldBe AdmissionPasteParse.Rejected
        }
    }

    @Test
    fun `a service url the camera would scan is not a paste`() {
        val other = "https://service.example/door?h=$hash"
        AdmissionQr.parse(other).shouldBeInstanceOf<AdmissionQrParse.Ready>()
        AdmissionPaste.parse(other) shouldBe AdmissionPasteParse.Rejected
    }

    @Test
    fun `a bare hash stays a camera rejection and is not a paste`() {
        AdmissionQr.parse(hash) shouldBe AdmissionQrParse.BareHash
        AdmissionPaste.parse(hash) shouldBe AdmissionPasteParse.Rejected
    }

    @Test
    fun `paste error copy is one plain line`() {
        val xml = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
        val error = Regex("""name="pcoc_admit_paste_error">([^<]*)</string>""")
            .find(xml)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        error shouldBe "That is not a sign-in code."
        error.contains("\n") shouldBe false
        error.contains(hash) shouldBe false
        error.contains("Exception") shouldBe false
    }
}
