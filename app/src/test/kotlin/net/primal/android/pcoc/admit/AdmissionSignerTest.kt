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
    fun `signs SHA-256 of challenge_bytes once and not the object hash or a second hash`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val privateKey = Hex.decode(pair.privateKey)
        val publicKey = Hex.decode(pair.pubKey)
        val objectSha = sha256(byteArrayOf(1, 2, 3))
        val link = linkFor()
        val message = sha256(SignInChallenge.challengeBytes(link, objectSha))

        val signatureHex = AdmissionSigner.sign(privateKey = privateKey, link = link, objectSha256 = objectSha)
        val signature = Hex.decode(signatureHex)

        signatureHex.matches(Regex("^[0-9a-f]{128}$")) shouldBe true
        CryptoUtils.verify(signature = signature, hash = message, pubKey = publicKey) shouldBe true
        CryptoUtils.verify(signature = signature, hash = sha256(message), pubKey = publicKey) shouldBe false
        CryptoUtils.verify(signature = signature, hash = objectSha, pubKey = publicKey) shouldBe false
        CryptoUtils.verify(signature = signature, hash = link.nonce(), pubKey = publicKey) shouldBe false
    }

    @Test
    fun `the message must be 32 bytes and the key must be 32 bytes`() {
        val privateKey = ByteArray(32) { 3 }
        shouldThrow<IllegalArgumentException> { AdmissionSigner.signMessage(privateKey, ByteArray(31)) }
        shouldThrow<IllegalArgumentException> { AdmissionSigner.signMessage(privateKey, ByteArray(109)) }
        shouldThrow<IllegalArgumentException> { AdmissionSigner.signMessage(ByteArray(31), ByteArray(32)) }
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
