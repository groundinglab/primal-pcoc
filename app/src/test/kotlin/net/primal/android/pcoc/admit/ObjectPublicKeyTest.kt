package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import java.io.File
import net.primal.android.pcoc.enroll.FileEnrolledPcocStore
import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import net.primal.domain.nostr.cryptography.utils.hexToNsecHrp
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

    @Test
    fun `formatted account text and a hex public key still match`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val key = Hex.decode(pair.pubKey)
        val objectBytes = cborPublicKeyMap(key)
        val npub = pair.pubKey.hexToNpubHrp()

        checkObjectKey("0x${pair.pubKey}", objectBytes) shouldBe ObjectKeyCheck.Match
        checkObjectKey("  ${pair.pubKey.uppercase()}  ", objectBytes) shouldBe ObjectKeyCheck.Match
        checkObjectKey(npub.uppercase(), objectBytes) shouldBe ObjectKeyCheck.Match
        checkObjectKey(pair.pubKey, hexTextPublicKey(pair.pubKey)) shouldBe ObjectKeyCheck.Match
        checkObjectKey(pair.pubKey, byteStringKeyMap(key)) shouldBe ObjectKeyCheck.Match

        val parsed = key.copyOf()
        val scanned = key.reversedArray()
        chooseObjectKey(parsed = parsed, scanned = scanned).contentEquals(parsed) shouldBe true
        chooseObjectKey(parsed = null, scanned = key).contentEquals(key) shouldBe true
    }

    @Test
    fun `a different signing key still matches the account and a matching signer opens confirm`() {
        val account = "11".repeat(32)
        val objectBytes = cborPublicKeyMap(ByteArray(PUBLIC_KEY_LEN) { 0x11 })
        val other = ByteArray(PUBLIC_KEY_LEN) { 0x22 }
        val withOtherSigner = planReadyScan(
            canSign = true,
            accountHex = account,
            objectBytes = objectBytes,
            signingKey = other,
        )
        withOtherSigner.followUp shouldBe ReadyScanFollowUp.Confirm
        withOtherSigner.pubkeyMatch shouldBe true

        val signerOnly = planReadyScan(
            canSign = true,
            accountHex = "22".repeat(32),
            objectBytes = objectBytes,
            signingKey = ByteArray(PUBLIC_KEY_LEN) { 0x11 },
        )
        signerOnly.followUp shouldBe ReadyScanFollowUp.Confirm
        signerOnly.keys?.loginPrefix shouldBe "11111111"
        signerOnly.keys?.filePrefix shouldBe "11111111"
    }

    @Test
    fun `mismatch log keeps an 8 character prefix and drops the rest of the key`() {
        val key = ByteArray(PUBLIC_KEY_LEN) { (it + 3).toByte() }
        val account = Hex.encode(key)
        val other = ByteArray(PUBLIC_KEY_LEN) { (it + 9).toByte() }
        val report = objectKeyReport(
            accountHex = account,
            signingKey = other,
            objectBytes = cborPublicKeyMap(other),
        )
        report.check shouldBe ObjectKeyCheck.Match
        val mismatch = objectKeyReport(
            accountHex = account,
            signingKey = null,
            objectBytes = cborPublicKeyMap(other),
        )
        mismatch.check shouldBe ObjectKeyCheck.Mismatch
        val log = admissionKeyLog(mismatch)
        log.contains(account) shouldBe false
        log.contains(Hex.encode(other)) shouldBe false
        log.contains("nsec") shouldBe false
        log.contains(mismatch.loginPrefix.orEmpty()) shouldBe true
        log.contains(mismatch.filePrefix.orEmpty()) shouldBe true
        mismatch.loginPrefix shouldBe account.lowercase().take(KEY_PREFIX_LEN)
        mismatch.filePrefix shouldBe Hex.encode(other).lowercase().take(KEY_PREFIX_LEN)
        val leaked = admissionKeyLog(
            ObjectKeyReport(
                check = ObjectKeyCheck.Mismatch,
                loginPrefix = account,
                filePrefix = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
            ),
        )
        leaked.contains(account) shouldBe false
        leaked.contains("nsec1") shouldBe false
        leaked.contains("login=none") shouldBe true
        leaked.contains("file=none") shouldBe true
    }

    @Test
    fun `a formatted account id reads the file stored for that login`() {
        val root = kotlin.io.path.createTempDirectory("pcoc-admit-account").toFile()
        val store = FileEnrolledPcocStore(root)
        val account = "ab".repeat(32)
        store.write(account, byteArrayOf(1, 2, 3))

        enrolledObject(store, "0x$account")!!.contentEquals(byteArrayOf(1, 2, 3)) shouldBe true
        enrolledObject(store, "  ${account.uppercase()}  ")!!.contentEquals(byteArrayOf(1, 2, 3)) shouldBe true
        accountHasObject(store, account.hexToNpubHrp()) shouldBe true
        File(root, "$account.bin").isFile shouldBe true
    }

    @Test
    fun `only the producer private key for that login is selected`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val other = CryptoUtils.generateHexEncodedKeypair()
        val nsec = pair.privateKey.hexToNsecHrp()
        val npub = pair.pubKey.hexToNpubHrp()
        val credentials = listOf(
            Credential(nsec = null, npub = npub, type = CredentialType.PublicKey),
            Credential(nsec = nsec, npub = npub.uppercase(), type = CredentialType.PrivateKey),
            Credential(
                nsec = other.privateKey.hexToNsecHrp(),
                npub = other.pubKey.hexToNpubHrp(),
                type = CredentialType.PrivateKey,
            ),
        )

        selectProducerCredential(credentials, "0x${pair.pubKey}")?.npub shouldBe npub.uppercase()
        selectProducerCredential(credentials, pair.pubKey.hexToNpubHrp())?.type shouldBe CredentialType.PrivateKey
        selectProducerCredential(credentials, other.pubKey)?.npub shouldBe other.pubKey.hexToNpubHrp()
        selectProducerCredential(credentials, "zz") shouldBe null
    }

    private fun hexTextPublicKey(hex: String): ByteArray {
        val name = "public_key".toByteArray(Charsets.UTF_8)
        val text = hex.toByteArray(Charsets.UTF_8)
        val out = ArrayList<Byte>()
        out += 0xa1.toByte()
        out += (0x60 or name.size).toByte()
        name.forEach { out += it }
        out += 0x78.toByte()
        out += text.size.toByte()
        text.forEach { out += it }
        return out.toByteArray()
    }

    private fun byteStringKeyMap(publicKey: ByteArray): ByteArray {
        val name = "public_key".toByteArray(Charsets.UTF_8)
        val out = ArrayList<Byte>()
        out += 0xa1.toByte()
        out += (0x40 or name.size).toByte()
        name.forEach { out += it }
        out += 0x58.toByte()
        out += publicKey.size.toByte()
        publicKey.forEach { out += it }
        return out.toByteArray()
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
