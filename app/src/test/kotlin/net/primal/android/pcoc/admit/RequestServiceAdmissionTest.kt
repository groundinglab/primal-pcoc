package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import org.junit.Test

class RequestServiceAdmissionTest {

    private val account = "11".repeat(32)
    private val objectBytes = cborPublicKeyMap(ByteArray(32) { 0x11 })
    private val privateKey = ByteArray(32) { 9 }

    @Test
    fun `a held object is signed and posted once with no confirm step`() {
        val store = memoryStore().apply { write(account, objectBytes) }
        val link = linkFor(objectBytes)
        val seen = mutableListOf<Post>()
        val request = RequestServiceAdmission(
            store = store,
            poster = recording(seen, AdmissionPostResult.Submitted),
            signLink = { _, signed ->
                signed shouldBe link
                "cd".repeat(64)
            },
        )

        request.submit(link = link, accountHex = account, privateKey = privateKey) shouldBe
            AdmissionSubmitOutcome.Submitted
        seen.single().link shouldBe link
        seen.single().bytes.contentEquals(objectBytes) shouldBe true
        seen.single().signature shouldBe "cd".repeat(64)
    }

    @Test
    fun `the signature is BIP-340 over SHA-256 of challenge_bytes`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val publicKey = Hex.decode(pair.pubKey)
        val held = cborPublicKeyMap(publicKey)
        val link = linkFor(held)
        val seen = mutableListOf<Post>()
        val request = RequestServiceAdmission(
            store = memoryStore().apply { write(pair.pubKey, held) },
            poster = recording(seen, AdmissionPostResult.Submitted),
        )

        request.submit(link, pair.pubKey, Hex.decode(pair.privateKey)) shouldBe AdmissionSubmitOutcome.Submitted

        val signature = Hex.decode(seen.single().signature)
        val challenge = SignInChallenge.challengeBytes(link)
        val message = sha256(challenge)
        CryptoUtils.verify(signature = signature, hash = message, pubKey = publicKey) shouldBe true
        CryptoUtils.verify(signature = signature, hash = sha256(message), pubKey = publicKey) shouldBe false
        CryptoUtils.verify(signature = signature, hash = link.objectSha256(), pubKey = publicKey) shouldBe false
    }

    @Test
    fun `the three client classes pass through`() {
        listOf(
            AdmissionPostResult.Submitted to AdmissionSubmitOutcome.Submitted,
            AdmissionPostResult.BadSignature to AdmissionSubmitOutcome.BadSignature,
            AdmissionPostResult.Malformed to AdmissionSubmitOutcome.Malformed,
            AdmissionPostResult.DeliveryFailed(DeliveryFailure.TimedOut) to
                AdmissionSubmitOutcome.DeliveryFailed(DeliveryFailure.TimedOut),
        ).forEach { (posted, expected) ->
            val request = RequestServiceAdmission(
                store = memoryStore().apply { write(account, objectBytes) },
                poster = { _, _, _ -> posted },
                signLink = { _, _ -> "cd".repeat(64) },
            )
            request.submit(linkFor(objectBytes), account, privateKey) shouldBe expected
        }
    }

    @Test
    fun `no object for the link hash means no signature and no post`() {
        val store = memoryStore()
        var posts = 0
        var signs = 0
        val request = RequestServiceAdmission(
            store = store,
            poster = { _, _, _ ->
                posts += 1
                AdmissionPostResult.Submitted
            },
            signLink = { _, _ ->
                signs += 1
                "cd".repeat(64)
            },
        )

        request.submit(linkFor(objectBytes), account, privateKey) shouldBe AdmissionSubmitOutcome.NoObject
        store.write(account, objectBytes)
        request.submit(linkFor(objectBytes + byteArrayOf(0)), account, privateKey) shouldBe
            AdmissionSubmitOutcome.ObjectNotHeld
        request.submit(linkFor(objectBytes), account, null) shouldBe AdmissionSubmitOutcome.WrongLogin
        request.submit(linkFor(objectBytes), account, ByteArray(31)) shouldBe AdmissionSubmitOutcome.WrongLogin
        posts shouldBe 0
        signs shouldBe 0
    }

    @Test
    fun `a different object public key is not signed or posted`() {
        val other = cborPublicKeyMap(ByteArray(32) { 0x22 })
        var calls = 0
        val request = RequestServiceAdmission(
            store = memoryStore().apply { write(account, other) },
            poster = { _, _, _ ->
                calls += 1
                AdmissionPostResult.Submitted
            },
            signLink = { _, _ ->
                calls += 1
                "cd".repeat(64)
            },
        )

        request.submit(linkFor(other), account, privateKey) shouldBe
            AdmissionSubmitOutcome.KeyMismatch(loginPrefix = "11111111", filePrefix = "22222222")
        calls shouldBe 0
    }

    @Test
    fun `an object with no public key is not posted`() {
        val keyless = byteArrayOf(4, 5, 6)
        var posts = 0
        val request = RequestServiceAdmission(
            store = memoryStore().apply { write(account, keyless) },
            poster = { _, _, _ ->
                posts += 1
                AdmissionPostResult.Submitted
            },
            signLink = { _, _ -> "cd".repeat(64) },
        )

        request.submit(linkFor(keyless), account, privateKey) shouldBe AdmissionSubmitOutcome.ObjectKeyUnreadable
        posts shouldBe 0
    }

    @Test
    fun `a spaced or npub account id still signs the saved object`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val held = cborPublicKeyMap(Hex.decode(pair.pubKey))
        var posts = 0
        val request = RequestServiceAdmission(
            store = memoryStore().apply { write(pair.pubKey, held) },
            poster = { _, _, _ ->
                posts += 1
                AdmissionPostResult.Submitted
            },
        )

        val key = Hex.decode(pair.privateKey)
        request.submit(linkFor(held), "  ${pair.pubKey.uppercase()}  ", key) shouldBe AdmissionSubmitOutcome.Submitted
        request.submit(linkFor(held), "0x${pair.pubKey}", key) shouldBe AdmissionSubmitOutcome.Submitted
        request.submit(linkFor(held), pair.pubKey.hexToNpubHrp(), key) shouldBe AdmissionSubmitOutcome.Submitted
        posts shouldBe 3
    }

    @Test
    fun `a signer failure is not sent`() {
        var posts = 0
        val request = RequestServiceAdmission(
            store = memoryStore().apply { write(account, objectBytes) },
            poster = { _, _, _ ->
                posts += 1
                AdmissionPostResult.Submitted
            },
            signLink = { _, _ -> throw IllegalArgumentException("no") },
        )

        request.submit(linkFor(objectBytes), account, privateKey) shouldBe
            AdmissionSubmitOutcome.DeliveryFailed(DeliveryFailure.NotSent)
        posts shouldBe 0
    }

    private fun recording(seen: MutableList<Post>, result: AdmissionPostResult) =
        AdmissionPoster { link, bytes, signatureHex ->
            seen += Post(link, bytes, signatureHex)
            result
        }

    private fun memoryStore() =
        object : EnrolledPcocStore {
            private val files = mutableMapOf<String, ByteArray>()
            override fun contains(userIdHex: String) = files.containsKey(userIdHex.lowercase())
            override fun read(userIdHex: String) = files[userIdHex.lowercase()]?.copyOf()
            override fun write(userIdHex: String, objectBytes: ByteArray) {
                files[userIdHex.lowercase()] = objectBytes.copyOf()
            }
            override fun delete(userIdHex: String) {
                files.remove(userIdHex.lowercase())
            }
        }

    private data class Post(val link: SignInLink, val bytes: ByteArray, val signature: String)
}
