package net.primal.android.pcoc.hike

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class YieldedPublicKeyTest {

    @Test
    fun `three discriminants stay apart`() {
        val key = ByteArray(ACCOUNT_KEY_SIZE) { it.toByte() }
        decodeYieldedPublicKey(byteArrayOf(YIELDED_TAG_PUBLIC_KEY.toByte()) + key)
            .shouldBeInstanceOf<YieldedPublicKey.PublicKey>()
            .bytes
            .contentEquals(key) shouldBe true
        decodeYieldedPublicKey(byteArrayOf(YIELDED_TAG_NO_PUBLIC_KEY.toByte())) shouldBe
            YieldedPublicKey.NoPublicKey
        decodeYieldedPublicKey(byteArrayOf(YIELDED_TAG_UNDECODABLE.toByte())) shouldBe
            YieldedPublicKey.Undecodable
    }

    @Test
    fun `malformed results are not collapsed into no public key or undecodable`() {
        shouldThrow<HikeReaderUnavailable> {
            decodeYieldedPublicKey(byteArrayOf(YIELDED_TAG_NO_PUBLIC_KEY.toByte(), 1))
        }
        shouldThrow<HikeReaderUnavailable> {
            decodeYieldedPublicKey(byteArrayOf(YIELDED_TAG_UNDECODABLE.toByte(), 1))
        }
        shouldThrow<HikeReaderUnavailable> {
            decodeYieldedPublicKey(byteArrayOf(YIELDED_TAG_PUBLIC_KEY.toByte(), 1))
        }
        shouldThrow<HikeReaderUnavailable> {
            decodeYieldedPublicKey(byteArrayOf(9))
        }
        shouldThrow<HikeReaderUnavailable> {
            decodeYieldedPublicKey(byteArrayOf())
        }
    }

    @Test
    fun `unlinked reader does not call native code`() {
        var calls = 0
        val reader = LinkedHikeYieldedPublicKeyReader(linked = false) {
            calls += 1
            byteArrayOf(YIELDED_TAG_NO_PUBLIC_KEY.toByte())
        }

        shouldThrow<HikeReaderUnavailable> {
            reader.yieldedPublicKey(byteArrayOf(1))
        }
        calls shouldBe 0
    }

    @Test
    fun `linked reader returns the native discriminant`() {
        val key = ByteArray(ACCOUNT_KEY_SIZE) { 7 }
        val reader = LinkedHikeYieldedPublicKeyReader(linked = true) {
            byteArrayOf(YIELDED_TAG_PUBLIC_KEY.toByte()) + key
        }

        reader.yieldedPublicKey(byteArrayOf(1))
            .shouldBeInstanceOf<YieldedPublicKey.PublicKey>()
            .bytes
            .contentEquals(key) shouldBe true
    }
}
