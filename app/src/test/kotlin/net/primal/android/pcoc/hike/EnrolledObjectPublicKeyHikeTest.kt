package net.primal.android.pcoc.hike

import io.kotest.matchers.shouldBe
import net.primal.android.pcoc.admit.enrolledObjectPublicKey
import org.junit.Test

class EnrolledObjectPublicKeyHikeTest {

    @Test
    fun `sample object public key is read from shortest and wide cbor`() {
        val hike = HikeObject.sample()
        enrolledObjectPublicKey(HikeObject.encode(hike))!!.contentEquals(hike.publicKey) shouldBe true
        enrolledObjectPublicKey(HikeObject.encodeWide(hike))!!.contentEquals(hike.publicKey) shouldBe true
    }
}
