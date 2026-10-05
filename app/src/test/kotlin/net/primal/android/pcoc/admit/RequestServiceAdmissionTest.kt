package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import org.junit.Test

class RequestServiceAdmissionTest {

    private val account = "11".repeat(32)
    private val hash = "ab".repeat(32)
    private val url = "https://service.example/door?h=$hash"
    private val objectBytes = byteArrayOf(4, 5, 6)
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
                AdmissionPostResult.Decision(AdmissionDecision.Accept)
            },
            signHash = { _, hashHex ->
                hashHex shouldBe hash
                "cd".repeat(64)
            },
        )

        request.submit(exactUrl = url, accountHex = account, privateKey = privateKey) shouldBe
            AdmissionSubmitOutcome.Decision(AdmissionDecision.Accept)
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
