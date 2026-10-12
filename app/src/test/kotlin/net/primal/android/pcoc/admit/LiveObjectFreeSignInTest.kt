package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import java.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import net.primal.core.utils.serialization.CommonJson
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Test

/**
 * The website link carries no object. The phone signs the hike object saved for
 * this login. The Gate's locked body (WIRE.md on pcoc main) includes `object`,
 * the 64-hex SHA-256 of those bytes.
 */
class LiveObjectFreeSignInTest {

    @Test
    fun `the live object-free link is parsed and the saved object is posted with its hash`() {
        val raw =
            "https://groundinglab.io/sign-in/?v=1" +
                "&nonce=d064b391e5ff4f31863bdd67066b8e248eff9380e972a277ec5f34fd1c4c04b9" +
                "&exp=1791621887"
        val link = (SignInLinks.parse(raw) as SignInLinkParse.Ready).link
        link.text shouldBe raw
        link.origin shouldBe "https://groundinglab.io"
        link.nonceHex shouldBe "d064b391e5ff4f31863bdd67066b8e248eff9380e972a277ec5f34fd1c4c04b9"
        link.exp shouldBe 1_791_621_887uL
        link.text.contains("object=") shouldBe false

        val pair = CryptoUtils.generateHexEncodedKeypair()
        val publicKey = Hex.decode(pair.pubKey)
        val saved = cborPublicKeyMap(publicKey)
        val factory = ScriptedFactory()
        val request = RequestServiceAdmission(
            store = memoryStore().apply { write(pair.pubKey, saved) },
            poster = AdmissionDoorClient(factory),
        )

        request.submit(
            link = link,
            accountHex = pair.pubKey,
            privateKey = Hex.decode(pair.privateKey),
        ) shouldBe AdmissionSubmitOutcome.Submitted

        val posted = factory.requests.single()
        posted.method shouldBe "POST"
        posted.url.toString() shouldBe SIGN_IN_DOOR
        posted.body!!.contentType().toString() shouldBe "application/json"
        val text = Buffer().also { posted.body!!.writeTo(it) }.readUtf8()
        val fields = CommonJson.parseToJsonElement(text).jsonObject
        val objectHash = Hex.encode(sha256(saved))

        fields.keys.toList() shouldBe listOf("origin", "nonce", "exp", "object", "object_b64", "signature")
        fields.getValue("origin").jsonPrimitive.content shouldBe "https://groundinglab.io"
        fields.getValue("nonce").jsonPrimitive.content shouldBe link.nonceHex
        fields.getValue("exp").jsonPrimitive.isString shouldBe false
        fields.getValue("exp").jsonPrimitive.content shouldBe "1791621887"
        fields.getValue("object").jsonPrimitive.content shouldBe objectHash
        objectHash.length shouldBe 64
        objectHash.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
        Base64.getDecoder().decode(fields.getValue("object_b64").jsonPrimitive.content).contentEquals(saved) shouldBe
            true
        text.contains("object_sha256") shouldBe false
        text.contains("nsec") shouldBe false

        val signature = Hex.decode(fields.getValue("signature").jsonPrimitive.content)
        val message = SignInChallenge.bip340Message(SignInChallenge.challengeBytes(link, sha256(saved)))
        CryptoUtils.verify(signature = signature, hash = message, pubKey = publicKey) shouldBe true
        val otherHash = sha256(saved).also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val otherMessage = SignInChallenge.bip340Message(SignInChallenge.challengeBytes(link, otherHash))
        CryptoUtils.verify(signature = signature, hash = otherMessage, pubKey = publicKey) shouldBe false
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

    private class ScriptedFactory : Call.Factory {
        val requests = mutableListOf<Request>()

        override fun newCall(request: Request): Call {
            requests += request
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_OK)
                .message("OK")
                .body("""{"delivery":"ok"}""".toResponseBody(JSON))
                .build()
            return ScriptedCall(request, response)
        }
    }

    private class ScriptedCall(
        private val request: Request,
        private val response: Response,
    ) : Call {
        override fun request(): Request = request

        override fun execute(): Response = response

        override fun enqueue(responseCallback: Callback) = error("unused")

        override fun cancel() = Unit

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = false

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = this
    }

    private companion object {
        const val HTTP_OK = 200
        val JSON = "application/json".toMediaType()
    }
}
