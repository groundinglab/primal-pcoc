package net.primal.android.pcoc.bridge

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

class PcocBridgeSmokeTest {

    @Test
    fun `the packaged engine uses live bari and the live index`() {
        PcocNative.bariUrl() shouldBe BARI_URL
        PcocNative.indexUrl() shouldBe INDEX_URL
        BARI_URL shouldBe "https://bari.groundinglab.io"
        INDEX_URL shouldBe "https://index.groundinglab.io"
    }

    @Test
    fun `junk bytes are unreadable without treating that as a verdict`() {
        val text = PcocNative.verify(byteArrayOf(0xff.toByte(), 0, 1), INDEX_URL, 2)
        val outcome = parseVerifyOutcome(text)
        outcome shouldBe VerifyNativeOutcome.Unreadable
        text.shouldContain("unreadable")
    }

    @Test
    fun `a canonical public key map yields that key`() {
        val key = ByteArray(32) { index -> if (index == 0) 0x11 else if (index == 31) 0x22 else 0 }
        val yielded = PcocNative.yieldedPublicKey(cborPublicKeyMap(key))
        yielded!!.contentEquals(key) shouldBe true
    }

    private fun cborPublicKeyMap(key: ByteArray): ByteArray {
        val name = "public_key".toByteArray(Charsets.US_ASCII)
        val out = ByteArray(2 + name.size + 2 + key.size)
        var index = 0
        out[index++] = 0xA1.toByte()
        out[index++] = (0x60 or name.size).toByte()
        name.copyInto(out, index)
        index += name.size
        out[index++] = 0x58
        out[index] = key.size.toByte()
        key.copyInto(out, index + 1)
        return out
    }
}
