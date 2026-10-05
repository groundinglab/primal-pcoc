package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import org.junit.Test

class ObjectPublicKeyTest {

    @Test
    fun `top level public key matches the account and a reversed key does not`() {
        val key = ByteArray(PUBLIC_KEY_LEN) { it.toByte() }
        val account = Hex.encode(key)
        val objectBytes = cborPublicKeyMap(key)

        enrolledObjectPublicKey(objectBytes)!!.contentEquals(key) shouldBe true
        checkObjectKey(account, objectBytes) shouldBe ObjectKeyCheck.Match
        checkObjectKey(account.uppercase(), objectBytes) shouldBe ObjectKeyCheck.Match
        checkObjectKey(account, cborPublicKeyMap(key.reversedArray())) shouldBe ObjectKeyCheck.Mismatch
        checkObjectKey("zz", objectBytes) shouldBe ObjectKeyCheck.Mismatch
    }

    @Test
    fun `public key after other fields is read and a nested key is not`() {
        val key = ByteArray(PUBLIC_KEY_LEN) { 0x44 }
        val withPad = mapWithLeadingPad(key)
        enrolledObjectPublicKey(withPad)!!.contentEquals(key) shouldBe true

        val nested = nestedOnly(key)
        enrolledObjectPublicKey(nested) shouldBe null
        checkObjectKey(Hex.encode(key), nested) shouldBe ObjectKeyCheck.Unreadable
    }

    @Test
    fun `missing short duplicate and truncated keys are unreadable`() {
        enrolledObjectPublicKey(byteArrayOf(0xa0.toByte())) shouldBe null
        enrolledObjectPublicKey(byteArrayOf(0x01)) shouldBe null
        enrolledObjectPublicKey(shortPublicKey()) shouldBe null
        enrolledObjectPublicKey(duplicatePublicKey()) shouldBe null
        enrolledObjectPublicKey(truncatedAfterKey()) shouldBe null
        checkObjectKey("11".repeat(32), byteArrayOf(4, 5, 6)) shouldBe ObjectKeyCheck.Unreadable
    }

    @Test
    fun `a matching object opens confirm and a mismatch does not`() {
        val key = ByteArray(PUBLIC_KEY_LEN) { 0x11 }
        val account = "11".repeat(32)
        val match = planReadyScan(canSign = true, accountHex = account, objectBytes = cborPublicKeyMap(key))
        match.followUp shouldBe ReadyScanFollowUp.Confirm
        match.pubkeyMatch shouldBe true

        val mismatch = planReadyScan(
            canSign = true,
            accountHex = account,
            objectBytes = cborPublicKeyMap(ByteArray(PUBLIC_KEY_LEN) { 0x22 }),
        )
        mismatch.followUp shouldBe ReadyScanFollowUp.KeyMismatch
        mismatch.pubkeyMatch shouldBe false

        val unsigned = planReadyScan(canSign = false, accountHex = account, objectBytes = cborPublicKeyMap(key))
        unsigned.followUp shouldBe ReadyScanFollowUp.WrongLogin
        unsigned.pubkeyMatch shouldBe true

        val missing = planReadyScan(canSign = true, accountHex = account, objectBytes = byteArrayOf(1))
        missing.followUp shouldBe ReadyScanFollowUp.ObjectKeyUnreadable
        missing.pubkeyMatch shouldBe false

        planReadyScan(canSign = true, accountHex = account, objectBytes = null).followUp shouldBe
            ReadyScanFollowUp.NoObject
    }

    private fun mapWithLeadingPad(publicKey: ByteArray): ByteArray {
        val pad = ByteArray(PAD_LEN) { 0x7 }
        val name = "public_key".toByteArray(Charsets.UTF_8)
        val out = ArrayList<Byte>()
        out += 0xa2.toByte()
        out += 0x63.toByte()
        out += 'p'.code.toByte()
        out += 'a'.code.toByte()
        out += 'd'.code.toByte()
        out += 0x58.toByte()
        out += PAD_LEN.toByte()
        pad.forEach { out += it }
        out += (0x60 or name.size).toByte()
        name.forEach { out += it }
        out += 0x58.toByte()
        out += publicKey.size.toByte()
        publicKey.forEach { out += it }
        return out.toByteArray()
    }

    private fun nestedOnly(publicKey: ByteArray): ByteArray {
        val inner = cborPublicKeyMap(publicKey)
        val out = ArrayList<Byte>()
        out += 0xa1.toByte()
        out += 0x64.toByte()
        "wrap".toByteArray().forEach { out += it }
        inner.forEach { out += it }
        return out.toByteArray()
    }

    private fun shortPublicKey(): ByteArray {
        val out = ArrayList<Byte>()
        out += 0xa1.toByte()
        out += 0x6a.toByte()
        "public_key".toByteArray().forEach { out += it }
        out += 0x42.toByte()
        out += 0x01.toByte()
        out += 0x02.toByte()
        return out.toByteArray()
    }

    private fun duplicatePublicKey(): ByteArray {
        val one = cborPublicKeyMap(ByteArray(PUBLIC_KEY_LEN) { 0x11 })
        val nameAndValue = one.copyOfRange(1, one.size)
        val out = ArrayList<Byte>()
        out += 0xa2.toByte()
        nameAndValue.forEach { out += it }
        nameAndValue.forEach { out += it }
        return out.toByteArray()
    }

    private fun truncatedAfterKey(): ByteArray {
        val one = cborPublicKeyMap(ByteArray(PUBLIC_KEY_LEN) { 0x11 })
        val out = ArrayList<Byte>()
        out += 0xa2.toByte()
        one.copyOfRange(1, one.size).forEach { out += it }
        return out.toByteArray()
    }

    private companion object {
        const val PUBLIC_KEY_LEN = 32
        const val PAD_LEN = 40
    }
}
