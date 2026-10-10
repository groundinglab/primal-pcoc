package net.primal.android.pcoc.hike

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import net.primal.android.pcoc.admit.ObjectKeyCheck
import net.primal.android.pcoc.admit.checkObjectKey
import net.primal.android.pcoc.admit.enrolledObjectPublicKey
import net.primal.android.pcoc.admit.objectKeyReport
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import org.junit.Test

class EnrolledObjectPublicKeyHikeTest {

    @Test
    fun `a canonical public_key map is the account key`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val key = Hex.decode(pair.pubKey)
        val bytes = cborPublicKeyMap(key)

        enrolledObjectPublicKey(bytes)!!.contentEquals(key) shouldBe true
        checkObjectKey(pair.pubKey, bytes) shouldBe ObjectKeyCheck.Match
        checkObjectKey(pair.pubKey.uppercase(), bytes) shouldBe ObjectKeyCheck.Match
        val report = objectKeyReport(accountHex = pair.pubKey, signingKey = null, objectBytes = bytes)
        report.keySource shouldBe "object"
        report.check shouldBe ObjectKeyCheck.Match
        report.readersDisagree shouldBe false
        checkObjectKey("33".repeat(32), bytes) shouldBe ObjectKeyCheck.Mismatch
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
