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
    fun `sample object public key is read from shortest and wide cbor`() {
        val hike = HikeObject.sample()
        enrolledObjectPublicKey(HikeObject.encode(hike))!!.contentEquals(hike.publicKey) shouldBe true
        enrolledObjectPublicKey(HikeObject.encodeWide(hike))!!.contentEquals(hike.publicKey) shouldBe true
    }

    @Test
    fun `a parsed hike matches the account that owns its public key`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val sample = HikeObject.sample()
        val hike = HikeObject.Hike(Hex.decode(pair.pubKey), sample.seedBeacon, sample.stages)
        val bytes = HikeObject.encode(hike)

        checkObjectKey(pair.pubKey, bytes) shouldBe ObjectKeyCheck.Match
        checkObjectKey(pair.pubKey.uppercase(), bytes) shouldBe ObjectKeyCheck.Match
        val report = objectKeyReport(accountHex = pair.pubKey, signingKey = null, objectBytes = bytes)
        report.keySource shouldBe "object"
        report.check shouldBe ObjectKeyCheck.Match
        checkObjectKey("33".repeat(32), bytes) shouldBe ObjectKeyCheck.Mismatch
    }
}
