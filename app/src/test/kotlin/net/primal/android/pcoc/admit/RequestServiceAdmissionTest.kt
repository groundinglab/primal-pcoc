package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import org.junit.Test

class RequestServiceAdmissionTest {

    private val account = "11".repeat(32)
    private val hash = "ab".repeat(32)
    private val url = "https://service.example/door?h=$hash"
    private val objectBytes = cborPublicKeyMap(ByteArray(32) { 0x11 })
    private val privateKey = ByteArray(32) { 9 }

    @Test
    fun `signs the hash and posts the object to the exact url`() {
        val store = memoryStore()
        store.write(account, objectBytes)
        val seen = mutableListOf<Post>()
        val request = RequestServiceAdmission(
            store = store,
            poster = AdmissionPoster { exactUrl, bytes, signatureHex ->
                seen += Post(exactUrl, bytes, signatureHex)
                AdmissionPostResult.Delivered
            },
            signHash = { _, hashHex ->
                hashHex shouldBe hash
                "cd".repeat(64)
            },
        )

        request.submit(exactUrl = url, accountHex = account, privateKey = privateKey) shouldBe
            AdmissionSubmitOutcome.Delivered
        seen.single().url shouldBe url
        seen.single().bytes.contentEquals(objectBytes) shouldBe true
        seen.single().signature shouldBe "cd".repeat(64)
    }

    @Test
    fun `no object, no key, bare hash, and the gate do not post`() {
        val store = memoryStore()
        var posts = 0
        var signs = 0
        val request = RequestServiceAdmission(
            store = store,
            poster = AdmissionPoster { _, _, _ ->
                posts += 1
                AdmissionPostResult.Failed
            },
            signHash = { _, _ ->
                signs += 1
                "cd".repeat(64)
            },
        )

        request.submit(url, account, privateKey) shouldBe AdmissionSubmitOutcome.NotSubmitted
        store.write(account, objectBytes)
        request.submit(url, account, null) shouldBe AdmissionSubmitOutcome.NotSubmitted
        request.submit(hash, account, privateKey) shouldBe AdmissionSubmitOutcome.NotSubmitted
        request.submit("https://admission.pcoc.app/v1/sign-in?h=$hash", account, privateKey) shouldBe
            AdmissionSubmitOutcome.NotSubmitted
        posts shouldBe 0
        signs shouldBe 0
    }

    @Test
    fun `a failed response is not a denial`() {
        val store = memoryStore()
        store.write(account, objectBytes)
        val request = RequestServiceAdmission(
            store = store,
            poster = AdmissionPoster { _, _, _ -> AdmissionPostResult.Failed },
            signHash = { _, _ -> "cd".repeat(64) },
        )

        request.submit(url, account, privateKey) shouldBe AdmissionSubmitOutcome.Failed
    }

    @Test
    fun `a different object public key is not posted`() {
        val store = memoryStore()
        store.write(account, cborPublicKeyMap(ByteArray(32) { 0x22 }))
        var posts = 0
        var signs = 0
        val request = RequestServiceAdmission(
            store = store,
            poster = AdmissionPoster { _, _, _ ->
                posts += 1
                AdmissionPostResult.Failed
            },
            signHash = { _, _ ->
                signs += 1
                "cd".repeat(64)
            },
        )

        val outcome = request.submit(url, account, privateKey)
        outcome shouldBe AdmissionSubmitOutcome.KeyMismatch(loginPrefix = "11111111", filePrefix = "22222222")
        posts shouldBe 0
        signs shouldBe 0
    }

    @Test
    fun `an object with no public key is not posted`() {
        val store = memoryStore()
        store.write(account, byteArrayOf(4, 5, 6))
        var posts = 0
        val request = RequestServiceAdmission(
            store = store,
            poster = AdmissionPoster { _, _, _ ->
                posts += 1
                AdmissionPostResult.Delivered
            },
            signHash = { _, _ -> "cd".repeat(64) },
        )

        request.submit(url, account, privateKey) shouldBe AdmissionSubmitOutcome.ObjectKeyUnreadable
        posts shouldBe 0
    }

    @Test
    fun `live sign-in url signs the scanned h bytes and posts that object`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val liveAccount = pair.pubKey
        val liveKey = Hex.decode(pair.privateKey)
        val liveObject = cborPublicKeyMap(Hex.decode(pair.pubKey))
        val liveHash = "01" + "ab".repeat(30) + "ff"
        val exact = "https://pcoc.app/sign-in/?h=$liveHash"
        val ready = AdmissionQr.parse(exact) as AdmissionQrParse.Ready
        val store = memoryStore()
        store.write(liveAccount, liveObject)
        val seen = mutableListOf<Post>()
        val request = RequestServiceAdmission(
            store = store,
            poster = AdmissionPoster { exactUrl, bytes, signatureHex ->
                seen += Post(exactUrl, bytes, signatureHex)
                AdmissionPostResult.Delivered
            },
        )

        val outcome = request.submit(exactUrl = exact, accountHex = liveAccount, privateKey = liveKey)
        outcome shouldBe AdmissionSubmitOutcome.Delivered
        val posted = seen.single()
        posted.url shouldBe exact
        posted.bytes.contentEquals(liveObject) shouldBe true
        posted.signature.length shouldBe 128
        posted.signature.matches(Regex("^[0-9a-f]{128}$")) shouldBe true
        CryptoUtils.verify(
            signature = Hex.decode(posted.signature),
            hash = ready.hashBytes,
            pubKey = Hex.decode(pair.pubKey),
        ) shouldBe true
        CryptoUtils.verify(
            signature = Hex.decode(posted.signature),
            hash = ready.hashBytes.reversedArray(),
            pubKey = Hex.decode(pair.pubKey),
        ) shouldBe false
        val json = AdmissionPayload.encode(objectBytes = posted.bytes, signatureHex = posted.signature)
        val fields = admissionBodyFields(json)
        fields.hasObjectB64 shouldBe true
        fields.hasSignature shouldBe true
        json.contains(liveHash) shouldBe false
    }

    @Test
    fun `the key that will sign is posted when the account text does not match`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val other = "22".repeat(32)
        val liveObject = cborPublicKeyMap(Hex.decode(pair.pubKey))
        val store = memoryStore()
        store.write(other, liveObject)
        val seen = mutableListOf<Post>()
        val request = RequestServiceAdmission(
            store = store,
            poster = AdmissionPoster { exactUrl, bytes, signatureHex ->
                seen += Post(exactUrl, bytes, signatureHex)
                AdmissionPostResult.Delivered
            },
        )

        request.submit(exactUrl = url, accountHex = other, privateKey = Hex.decode(pair.privateKey)) shouldBe
            AdmissionSubmitOutcome.Delivered
        val posted = seen.single()
        posted.bytes.contentEquals(liveObject) shouldBe true
        CryptoUtils.verify(
            signature = Hex.decode(posted.signature),
            hash = Hex.decode(hash),
            pubKey = Hex.decode(pair.pubKey),
        ) shouldBe true
    }

    @Test
    fun `a spaced or npub account id still signs the saved object`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val liveObject = cborPublicKeyMap(Hex.decode(pair.pubKey))
        val store = memoryStore()
        store.write(pair.pubKey, liveObject)
        var posts = 0
        val request = RequestServiceAdmission(
            store = store,
            poster = AdmissionPoster { _, _, _ ->
                posts += 1
                AdmissionPostResult.Delivered
            },
        )

        val npub = pair.pubKey.hexToNpubHrp()
        request.submit(url, "  ${pair.pubKey.uppercase()}  ", Hex.decode(pair.privateKey)) shouldBe
            AdmissionSubmitOutcome.Delivered
        request.submit(url, "0x${pair.pubKey}", Hex.decode(pair.privateKey)) shouldBe
            AdmissionSubmitOutcome.Delivered
        request.submit(url, npub, Hex.decode(pair.privateKey)) shouldBe
            AdmissionSubmitOutcome.Delivered
        posts shouldBe 3
    }

    @Test
    fun `a bad signature is not a refusal`() {
        val store = memoryStore()
        store.write(account, objectBytes)
        val request = RequestServiceAdmission(
            store = store,
            poster = AdmissionPoster { _, _, _ -> AdmissionPostResult.BadSignature },
            signHash = { _, _ -> "cd".repeat(64) },
        )

        request.submit(url, account, privateKey) shouldBe AdmissionSubmitOutcome.BadSignature
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

    private data class Post(val url: String, val bytes: ByteArray, val signature: String)
}
