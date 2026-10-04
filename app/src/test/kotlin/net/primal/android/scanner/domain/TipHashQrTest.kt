package net.primal.android.scanner.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class TipHashQrTest {

    private val displayOrderHash = "01" + "ab".repeat(30) + "ff"

    @Test
    fun parse_accepts64HexInDisplayOrder() {
        TipHashQr.parse(displayOrderHash) shouldBe displayOrderHash
        TipHashQr.parse(displayOrderHash.uppercase()) shouldBe displayOrderHash
        TipHashQr.parse("  0x$displayOrderHash\n") shouldBe displayOrderHash
    }

    @Test
    fun parse_keepsBitcoinDisplayOrder() {
        val parsed = TipHashQr.parse(displayOrderHash)
        parsed!!.take(2) shouldBe "01"
        parsed.takeLast(2) shouldBe "ff"
    }

    @Test
    fun parse_extractsHashAndIgnoresHeight() {
        TipHashQr.parse("height 840000\n$displayOrderHash") shouldBe displayOrderHash
        TipHashQr.parse("840000") shouldBeNull()
        TipHashQr.parse("height: 840000") shouldBeNull()
    }

    @Test
    fun parse_rejectsAmbiguousOrShortPayloads() {
        TipHashQr.parse("abcd") shouldBeNull()
        TipHashQr.parse("$displayOrderHash $displayOrderHash") shouldBeNull()
        TipHashQr.parse("") shouldBeNull()
    }

    @Test
    fun from_classifiesOnlyTheTipHash() {
        QrCodeDataType.from(displayOrderHash) shouldBe QrCodeDataType.TIP_HASH
        QrCodeDataType.from("840000") shouldBe null
    }
}
