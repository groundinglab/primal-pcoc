package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.android.pcoc.admit.RequestAdmissionContract.ScanProblem
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import net.primal.android.scanner.domain.QrCodeDataType
import net.primal.core.utils.serialization.CommonJson
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import okhttp3.OkHttpClient
import org.junit.Test

class FlushWireTest {

    private val nonce = "0123456789abcdef".repeat(4)
    private val canonical = "https://groundinglab.io/flush/?v=1&nonce=$nonce&exp=1893456000"

    @Test
    fun `a flush link parses with the same strictness as sign-in`() {
        val ready = GateLinks.parse("  $canonical\r\n").shouldBeInstanceOf<GateLinkParse.Ready>()
        ready.link.kind shouldBe GateLinkKind.Flush
        ready.link.text shouldBe canonical
        ready.link.origin shouldBe "https://groundinglab.io"
        ready.link.nonceHex shouldBe nonce
        ready.link.exp shouldBe 1_893_456_000uL
        SignInLinks.parse(canonical) shouldBe SignInLinkParse.NotASignInLink

        val signIn = GateLinks.parse(linkFor(nonceHex = nonce).text).shouldBeInstanceOf<GateLinkParse.Ready>()
        signIn.link.kind shouldBe GateLinkKind.SignIn

        fun withExp(exp: String) = canonical.replace("exp=1893456000", "exp=$exp")
        (GateLinks.parse(withExp("0")) as GateLinkParse.Ready).link.exp shouldBe 0uL
        (GateLinks.parse(withExp("18446744073709551615")) as GateLinkParse.Ready).link.exp shouldBe ULong.MAX_VALUE
        GateLinks.parse(withExp("18446744073709551616")) shouldBe GateLinkParse.NotALink
        GateLinks.parse(withExp("00")) shouldBe GateLinkParse.NotALink

        listOf(
            canonical.replace("https://", "http://"),
            canonical.replace("https://groundinglab.io", "https://www.groundinglab.io"),
            canonical.replace("groundinglab.io", "groundinglab.io:443"),
            canonical.replace("groundinglab.io", "groundinglab.io:8443"),
            "https://user@groundinglab.io/flush/?v=1&nonce=$nonce&exp=1893456000",
            "$canonical#x",
            "$canonical&x=1",
            canonical.replace("/flush/", "/flush"),
            canonical.replace(nonce, nonce.uppercase()),
            "https://pcoc.app/flush/?v=1&nonce=$nonce&exp=1893456000",
            "https://www.pcoc.app/flush/?v=1&nonce=$nonce&exp=1893456000",
            "http://pcoc.app/flush/?v=1&nonce=$nonce&exp=1893456000",
            "https://admission.groundinglab.io/v1/flush",
        ).forEach { GateLinks.parse(it) shouldBe GateLinkParse.NotALink }
    }

    @Test
    fun `the general scanner claims a flush link and leaves the rejects alone`() {
        QrCodeDataType.from(canonical) shouldBe QrCodeDataType.PCOC_SERVICE_URL
        listOf(
            canonical.replace("https://", "http://"),
            canonical.replace("https://groundinglab.io", "https://www.groundinglab.io"),
            "$canonical#x",
            "$canonical&x=1",
            "https://pcoc.app/flush/?v=1&nonce=$nonce&exp=1893456000",
        ).forEach { (QrCodeDataType.from(it) == QrCodeDataType.PCOC_SERVICE_URL) shouldBe false }
    }

    @Test
    fun `scan paste and an opened flush link share one grammar`() {
        val entered = classifyLink(canonical, LinkSource.Entered).shouldBeInstanceOf<LinkInput.Ready>()
        entered.link.kind shouldBe GateLinkKind.Flush
        val opened = classifyLink(canonical, LinkSource.AppLink).shouldBeInstanceOf<LinkInput.Ready>()
        opened.link.text shouldBe canonical

        listOf(FLUSH_APP_LINK, "$SIGN_IN_ORIGIN/flush", SIGN_IN_ORIGIN).forEach { bare ->
            classifyLink(bare, LinkSource.AppLink) shouldBe LinkInput.Ignore
        }
        val www = canonical.replace("https://groundinglab.io", "https://www.groundinglab.io")
        classifyLink(www, LinkSource.AppLink) shouldBe
            LinkInput.Rejected(ScanProblem.NotAResetLink, "The host is www.")
        classifyLink(canonical.replace("https://", "http://"), LinkSource.Entered) shouldBe
            LinkInput.Rejected(ScanProblem.NotAResetLink, "It is not https.")
        classifyLink(canonical.replace("groundinglab.io", "pcoc.app"), LinkSource.AppLink) shouldBe
            LinkInput.Rejected(ScanProblem.NotAResetLink, "pcoc.app is retired.")
        classifyLink("$canonical&x=1", LinkSource.Entered) shouldBe
            LinkInput.Rejected(ScanProblem.NotAResetLink, "It has an extra parameter.")
        classifyLink("$canonical#frag", LinkSource.Entered) shouldBe
            LinkInput.Rejected(ScanProblem.NotAResetLink, "It has a fragment.")

        readsSignInResult(GateLinkKind.SignIn) shouldBe true
        readsSignInResult(GateLinkKind.Flush) shouldBe false
        SignInNotices.scan(ready = false, why = "The host is www.", flush = true).userText shouldBe
            "Not a Reset link. The host is www."
        SignInNotices.submit(DeliveryFailure.NotSent, kind = GateLinkKind.Flush).userText shouldBe
            "The reset was not sent."
    }

    @Test
    fun `flush challenge domain ends in the login pubkey and is hashed once`() {
        val pair = CryptoUtils.generateHexEncodedKeypair()
        val privateKey = Hex.decode(pair.privateKey)
        val publicKey = Hex.decode(pair.pubKey)
        val link = flushLinkFor(nonceHex = nonce)
        val bytes = SignInChallenge.challengeBytes(link, publicKey)
        val domain = "pcoc-gate-flush-v1".toByteArray(Charsets.US_ASCII)
        val origin = "https://groundinglab.io".toByteArray(Charsets.UTF_8)

        bytes.copyOfRange(0, domain.size).contentEquals(domain) shouldBe true
        bytes[domain.size] shouldBe 0
        bytes[domain.size + 1] shouldBe origin.size.toByte()
        origin.size shouldBe 23
        val originAt = domain.size + 2
        bytes.copyOfRange(originAt, originAt + origin.size).contentEquals(origin) shouldBe true
        bytes.copyOfRange(bytes.size - publicKey.size, bytes.size).contentEquals(publicKey) shouldBe true
        bytes.copyOfRange(0, "pcoc-gate-signin-v1".length).contentEquals(
            "pcoc-gate-signin-v1".toByteArray(Charsets.US_ASCII),
        ) shouldBe false

        val signature = Hex.decode(AdmissionSigner.signFlush(privateKey, link, publicKey))
        val message = sha256(bytes)
        CryptoUtils.verify(signature = signature, hash = message, pubKey = publicKey) shouldBe true
        CryptoUtils.verify(signature = signature, hash = sha256(message), pubKey = publicKey) shouldBe false
        val signInBytes = SignInChallenge.challengeBytes(linkFor(nonceHex = nonce, exp = link.exp), publicKey)
        CryptoUtils.verify(signature = signature, hash = sha256(signInBytes), pubKey = publicKey) shouldBe false
        shouldThrow<IllegalArgumentException> { AdmissionSigner.sign(privateKey, link, publicKey) }
        shouldThrow<IllegalArgumentException> {
            AdmissionSigner.signFlush(privateKey, linkFor(nonceHex = nonce), publicKey)
        }
    }

    @Test
    fun `flush without an enrolled object posts five fields to v1 flush`() {
        val seen = mutableListOf<Posted>()
        val server = localFlushDoor(seen)
        val port = server.address.port
        val refused = mutableListOf<String>()
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.start()
        try {
            val pair = CryptoUtils.generateHexEncodedKeypair()
            val privateKey = Hex.decode(pair.privateKey)
            val link = flushLinkFor(nonceHex = nonce)
            val door = "http://127.0.0.1:$port/v1/flush"
            val client = AdmissionDoorClient(flushOnlyClient(port, refused), flushDoor = door)
            val request = RequestServiceAdmission(
                store = emptyStore(),
                poster = client,
                flushPoster = client,
            )

            request.submit(link, pair.pubKey, privateKey) shouldBe AdmissionSubmitOutcome.Submitted

            refused shouldBe emptyList()
            val posted = seen.single()
            posted.method shouldBe "POST"
            posted.path shouldBe "/v1/flush"
            posted.path.contains("sign-in") shouldBe false
            val fields = CommonJson.parseToJsonElement(posted.body).jsonObject
            fields.keys.toList() shouldBe listOf("origin", "nonce", "exp", "public_key", "signature")
            fields.getValue("exp").jsonPrimitive.isString shouldBe false
            fields.getValue("public_key").jsonPrimitive.content shouldBe pair.pubKey.lowercase()
            posted.body.contains("nsec") shouldBe false
            posted.body.contains("object_b64") shouldBe false
            posted.body.contains("yielded_public_key") shouldBe false
            posted.body.contains("\"object\"") shouldBe false
            privateKey.fill(0)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `flush without an nsec posts nothing`() {
        var posts = 0
        val request = RequestServiceAdmission(
            store = emptyStore(),
            poster = { _, _, _ ->
                posts += 1
                AdmissionPostResult.Submitted
            },
            flushPoster = { _, _, _ ->
                posts += 1
                AdmissionPostResult.Submitted
            },
        )

        request.submit(flushLinkFor(), "11".repeat(32), null) shouldBe AdmissionSubmitOutcome.WrongLogin
        request.submit(flushLinkFor(), "11".repeat(32), ByteArray(31)) shouldBe AdmissionSubmitOutcome.WrongLogin
        posts shouldBe 0
    }

    /**
     * The unit-test OkHttp is the Android jar, so MockWebServer 4.12 cannot start
     * (`okhttp3.internal.Util` is absent). This door is the same check: one local
     * `/v1/flush`, and any other URL is refused before a socket opens.
     */
    private fun localFlushDoor(seen: MutableList<Posted>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Posted(method = exchange.requestMethod, path = exchange.requestURI.path, body = body)
            val flushed = exchange.requestMethod == "POST" && exchange.requestURI.path == "/v1/flush"
            val bytes = (if (flushed) """{"flushed":true}""" else """{"no":true}""").toByteArray()
            exchange.sendResponseHeaders(if (flushed) HTTP_OK else HTTP_NOT_FOUND, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        return server
    }

    private fun flushOnlyClient(port: Int, refused: MutableList<String>): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val url = chain.request().url
                val allowed = url.host == "127.0.0.1" && url.port == port && url.encodedPath == "/v1/flush"
                if (!allowed) {
                    refused += url.toString()
                    throw IOException("refused ${url.encodedPath}")
                }
                chain.proceed(chain.request())
            }
            .build()

    private data class Posted(val method: String, val path: String, val body: String)

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
    }

    private fun emptyStore() =
        object : EnrolledPcocStore {
            override fun contains(userIdHex: String) = false
            override fun read(userIdHex: String): ByteArray? = null
            override fun write(userIdHex: String, objectBytes: ByteArray) = error("flush does not write an object")
            override fun delete(userIdHex: String) = Unit
        }
}
