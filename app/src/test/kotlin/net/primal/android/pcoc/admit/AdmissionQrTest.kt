package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.primal.android.scanner.domain.QrCodeDataType
import org.junit.Test

class AdmissionQrTest {

    private val hash = "ab".repeat(32)

    @Test
    fun `live pcoc sign-in url is ready in bitcoin display order`() {
        val hash = "01" + "ab".repeat(30) + "ff"
        val exact = "https://pcoc.app/sign-in/?h=$hash"
        val parsed = AdmissionQr.parse(exact).shouldBeInstanceOf<AdmissionQrParse.Ready>()

        parsed.exactUrl shouldBe exact
        parsed.hashHex shouldBe hash
        parsed.hashBytes.size shouldBe 32
        parsed.hashBytes.first() shouldBe 0x01.toByte()
        parsed.hashBytes.last() shouldBe 0xff.toByte()
        parsed.hashBytes.contentEquals(Hex.decode(hash)) shouldBe true
        QrCodeDataType.from(exact) shouldBe QrCodeDataType.PCOC_SERVICE_URL
    }

    @Test
    fun `www sign-in url keeps the scanned shape`() {
        val exact = "https://www.pcoc.app/sign-in/?h=$hash"
        val parsed = AdmissionQr.parse(exact).shouldBeInstanceOf<AdmissionQrParse.Ready>()

        parsed.exactUrl shouldBe exact
        parsed.hashHex shouldBe hash
    }

    @Test
    fun `https url with one h query is ready and keeps the scanned url`() {
        val exact = "https://service.example/door?x=1&h=$hash"
        val parsed = AdmissionQr.parse(exact).shouldBeInstanceOf<AdmissionQrParse.Ready>()

        parsed.exactUrl shouldBe exact
        parsed.hashHex shouldBe hash
        parsed.hashBytes.size shouldBe 32
    }

    @Test
    fun `uppercase hash decodes and the url stays exact`() {
        val exact = "https://service.example/door?h=${hash.uppercase()}"
        val parsed = AdmissionQr.parse(exact).shouldBeInstanceOf<AdmissionQrParse.Ready>()

        parsed.exactUrl shouldBe exact
        parsed.hashHex shouldBe hash
    }

    @Test
    fun `bare hash is not an https service url`() {
        AdmissionQr.parse(hash) shouldBe AdmissionQrParse.BareHash
        AdmissionQr.parse("0x$hash") shouldBe AdmissionQrParse.BareHash
    }

    @Test
    fun `http and a missing hash do not become ready`() {
        AdmissionQr.parse("http://service.example/door?h=$hash") shouldBe AdmissionQrParse.NotHttps
        AdmissionQr.parse("https://service.example/door") shouldBe AdmissionQrParse.MissingOrBadHash
        AdmissionQr.parse("https://service.example/door?h=abc") shouldBe AdmissionQrParse.MissingOrBadHash
        AdmissionQr.parse("https://service.example/door?h=$hash&h=$hash") shouldBe
            AdmissionQrParse.MissingOrBadHash
    }

    @Test
    fun `admission gate host is refused before it can be posted`() {
        AdmissionQr.parse("https://admission.pcoc.app/v1/sign-in?h=$hash") shouldBe AdmissionQrParse.GateUrl
        AdmissionQr.parse("https://ADMISSION.PCOC.APP/v1/sign-in?h=$hash") shouldBe AdmissionQrParse.GateUrl
        AdmissionQr.parse("https://www.pcoc.app/sign-in?h=$hash")
            .shouldBeInstanceOf<AdmissionQrParse.Ready>()
    }
}
