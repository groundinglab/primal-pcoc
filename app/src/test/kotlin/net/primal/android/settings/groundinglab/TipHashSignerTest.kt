package net.primal.android.settings.groundinglab

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import net.primal.android.scanner.domain.TipHashQr
import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import net.primal.domain.nostr.cryptography.utils.hexToNsecHrp
import org.junit.Test

class TipHashSignerTest {

    @Test
    fun producerPrivateKey_isThePrivateKeyNsecOnly() {
        val keyPair = CryptoUtils.generateHexEncodedKeypair()
        val nsec = keyPair.privateKey.hexToNsecHrp()
        val npub = keyPair.pubKey.hexToNpubHrp()

        Credential(nsec = nsec, npub = npub, type = CredentialType.PrivateKey)
            .producerPrivateKeyOrNull() shouldBe Hex.decode(keyPair.privateKey)

        Credential(nsec = nsec, npub = npub, type = CredentialType.InternalSigner)
            .producerPrivateKeyOrNull()
            .shouldBeNull()
        Credential(nsec = null, npub = npub, type = CredentialType.PublicKey)
            .producerPrivateKeyOrNull()
            .shouldBeNull()
        Credential(nsec = null, npub = npub, type = CredentialType.ExternalSigner)
            .producerPrivateKeyOrNull()
            .shouldBeNull()
    }

    @Test
    fun sign_isSchnorrOverDisplayOrderHashBytesNotTheHeight() {
        val keyPair = CryptoUtils.generateHexEncodedKeypair()
        val privateKey = Hex.decode(keyPair.privateKey)
        val displayOrderHash = "01" + "00".repeat(30) + "ff"
        val parsed = TipHashQr.parse("840000 $displayOrderHash")!!
        parsed shouldBe displayOrderHash

        val signatureHex = TipHashSigner.sign(privateKey = privateKey, tipHashHex = parsed)
        signatureHex.length shouldBe 128
        val signature = Hex.decode(signatureHex)
        val hash = Hex.decode(parsed)
        hash.size shouldBe 32
        hash.first() shouldBe 0x01.toByte()
        hash.last() shouldBe 0xff.toByte()

        CryptoUtils.verify(
            signature = signature,
            hash = hash,
            pubKey = Hex.decode(keyPair.pubKey),
        ) shouldBe true

        hash.contentEquals("840000".encodeToByteArray()) shouldBe false
    }
}
