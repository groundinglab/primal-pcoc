package net.primal.android.scanner.analysis

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class YPlaneTest {

    @Test
    fun packedPlaneIsUnchanged() {
        val y = byteArrayOf(1, 2, 3, 4)
        packYPlane(y = y, width = 2, height = 2, rowStride = 2, pixelStride = 1)!!
            .contentEquals(y) shouldBe true
    }

    @Test
    fun rowPaddingIsRemoved() {
        val y = byteArrayOf(
            1, 2, 9, 9,
            3, 4, 9, 9,
        )
        val packed = packYPlane(y = y, width = 2, height = 2, rowStride = 4, pixelStride = 1)

        packed!!.contentEquals(byteArrayOf(1, 2, 3, 4)) shouldBe true
    }

    @Test
    fun pixelStrideIsHonored() {
        val y = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val packed = packYPlane(y = y, width = 2, height = 2, rowStride = 4, pixelStride = 2)

        packed!!.contentEquals(byteArrayOf(1, 2, 3, 4)) shouldBe true
    }

    @Test
    fun shortBufferIsRefused() {
        packYPlane(y = byteArrayOf(1, 2, 3), width = 2, height = 2, rowStride = 2, pixelStride = 1)
            .shouldBeNull()
    }
}

class QrTextTest {

    @Test
    fun prefersRawThenDisplayThenUrl() {
        val link = "https://pcoc.app/sign-in/?v=1&nonce=${"01".repeat(32)}&exp=1893456000"
        chooseQrText(raw = link, display = "other", url = "url") shouldBe link
        chooseQrText(raw = "", display = link, url = null) shouldBe link
        chooseQrText(raw = null, display = null, url = link) shouldBe link
        chooseQrText(raw = null, display = " ", url = null) shouldBe " "
    }
}
