package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import net.primal.domain.nostr.cryptography.utils.hexToNsecHrp
import org.junit.Test

class AdmissionSignerTest {

    @Test
    fun `signs the 32 hash bytes with BIP-340 and not the reversed bytes`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val privateKey = Hex.decode(pair.privateKey)
        val publicKey = Hex.decode(pair.pubKey)
        val hashBytes = ByteArray(32) { (it + 1).toByte() }
        val hashHex = Hex.encode(hashBytes)

        val signatureHex = AdmissionSigner.sign(privateKey = privateKey, hashHex = hashHex)
        val signature = Hex.decode(signatureHex)

        signatureHex.length shouldBe 128
        signatureHex.matches(Regex("^[0-9a-f]{128}$")) shouldBe true
        CryptoUtils.verify(signature = signature, hash = hashBytes, pubKey = publicKey) shouldBe true
        CryptoUtils.verify(
            signature = signature,
            hash = hashBytes.reversedArray(),
            pubKey = publicKey,
        ) shouldBe false
    }

    @Test
    fun `refuses to sign hex text, a height, or a url`() {
        val privateKey = ByteArray(32) { 3 }
        shouldThrow<IllegalArgumentException> {
            AdmissionSigner.sign(privateKey, "not-a-hash")
        }
        shouldThrow<IllegalArgumentException> {
            AdmissionSigner.sign(privateKey, "100")
        }
        shouldThrow<IllegalArgumentException> {
            AdmissionSigner.sign(privateKey, "https://service.example/door?h=" + "ab".repeat(32))
        }
    }

    @Test
    fun `only a private key credential returns producer bytes`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val nsec = pair.privateKey.hexToNsecHrp()
        val npub = pair.pubKey.hexToNpubHrp()

        Credential(nsec = nsec, npub = npub, type = CredentialType.PrivateKey)
            .admissionPrivateKeyOrNull()
            ?.contentEquals(Hex.decode(pair.privateKey)) shouldBe true
        Credential(nsec = nsec, npub = npub, type = CredentialType.InternalSigner)
            .admissionPrivateKeyOrNull() shouldBe null
        Credential(nsec = null, npub = npub, type = CredentialType.PublicKey)
            .admissionPrivateKeyOrNull() shouldBe null
        Credential(nsec = null, npub = npub, type = CredentialType.ExternalSigner)
            .admissionPrivateKeyOrNull() shouldBe null
    }
}
