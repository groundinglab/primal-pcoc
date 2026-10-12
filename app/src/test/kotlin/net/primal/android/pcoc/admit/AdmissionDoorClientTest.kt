package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.serialization.json.jsonObject
import net.primal.core.utils.serialization.CommonJson
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

class AdmissionDoorClientTest {

    private val objectBytes = byteArrayOf(0xfb.toByte(), 1, 2)
    private val link = linkFor()
    private val signature = "cd".repeat(64)

    @Test
    fun `posts the six fields once to the fixed door and never to the scanned link`() {
        val factory = scripted(body = """{"delivery":"ok"}""")

        AdmissionDoorClient(factory).post(link, objectBytes, signature) shouldBe AdmissionPostResult.Submitted

        val recorded = factory.requests.single()
        recorded.method shouldBe "POST"
        recorded.url.toString() shouldBe "https://admission.groundinglab.io/v1/sign-in"
        recorded.url.toString().contains("groundinglab.io/sign-in/") shouldBe false
        recorded.body!!.contentType().toString() shouldContain "application/json"
        recorded.headers.names().any { it.equals("Authorization", ignoreCase = true) } shouldBe false
        val body = Buffer().also { recorded.body!!.writeTo(it) }.readUtf8()
        val fields = CommonJson.parseToJsonElement(body).jsonObject
        fields.keys shouldBe setOf("origin", "nonce", "exp", "object", "object_b64", "signature")
        body.contains("nsec") shouldBe false
    }

    @Test
    fun `the two HTTP 400 refusals are bad signature and malformed`() {
        post(code = 400, body = """{"error":{"code":"bad_signature"}}""") shouldBe AdmissionPostResult.BadSignature
        post(code = 400, body = """{"error":{"code":"malformed"}}""") shouldBe AdmissionPostResult.Malformed
    }

    @Test
    fun `any other HTTP 400 is not a delivery and not a verdict`() {
        val proxyBadRequest =
            """{"decision":"deny","error":{"code":"bad_request","message":"h is not 64 lowercase hex"}}"""
        listOf(proxyBadRequest, "", "not-json", """{"error":"bad_signature"}""", """{"decision":"deny"}""").forEach {
            post(code = 400, body = it) shouldBe AdmissionPostResult.DeliveryFailed(DeliveryFailure.UnexpectedResponse)
        }
    }

    @Test
    fun `every other completed response is sign-in submitted`() {
        val bodies = mapOf(
            200 to """{"delivery":"ok"}""",
            201 to "",
            413 to """{"error":{"code":"oversize"}}""",
            415 to """{"error":{"code":"unsupported_media_type"}}""",
            429 to """{"error":{"code":"rate_limited"}}""",
            500 to "boom",
            502 to """{"decision":"deny","error":{"code":"bad_gateway"}}""",
            503 to """{"error":{"code":"unavailable"}}""",
        )
        bodies.forEach { (code, body) -> post(code = code, body = body) shouldBe AdmissionPostResult.Submitted }
        listOf(
            """{"decision":"provisional","phase":"fast"}""",
            """{"decision":"deny","reasons":["earlier_version"]}""",
            """{"decision":"accept","verifier_ran":true}""",
            """{"error":{"code":"bad_signature"}}""",
        ).forEach { post(code = 200, body = it) shouldBe AdmissionPostResult.Submitted }
    }

    @Test
    fun `a redirect is a delivery failure`() {
        post(code = 302, body = """{"error":{"code":"malformed"}}""") shouldBe
            AdmissionPostResult.DeliveryFailed(DeliveryFailure.Redirected)

        val request = Request.Builder().url(SIGN_IN_DOOR).build()
        val prior = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(307).message("x").build()
        val followed = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"delivery":"ok"}""".toResponseBody(JSON))
            .priorResponse(prior)
            .build()
        interpretAdmissionResponse(followed) shouldBe AdmissionPostResult.DeliveryFailed(DeliveryFailure.Redirected)
    }

    @Test
    fun `transport failures are named and the post is not retried`() {
        val timeout = ScriptedFactory(failure = SocketTimeoutException("timed out"))
        AdmissionDoorClient(timeout).post(link, objectBytes, signature) shouldBe
            AdmissionPostResult.DeliveryFailed(DeliveryFailure.TimedOut)
        timeout.requests.size shouldBe 1

        val offline = ScriptedFactory(failure = UnknownHostException("admission.groundinglab.io"))
        AdmissionDoorClient(offline).post(link, objectBytes, signature) shouldBe
            AdmissionPostResult.DeliveryFailed(DeliveryFailure.NoConnection)
        offline.requests.size shouldBe 1

        val closed = AdmissionDoorClient(Call.Factory { throw IllegalStateException("closed") })
        closed.post(link, objectBytes, signature) shouldBe
            AdmissionPostResult.DeliveryFailed(DeliveryFailure.NoConnection)
    }

    @Test
    fun `a signature that is not 128 lowercase hex is not sent`() {
        val client = AdmissionDoorClient(Call.Factory { error("posted") })
        val notSent = AdmissionPostResult.DeliveryFailed(DeliveryFailure.NotSent)
        client.post(link, objectBytes, "zz".repeat(64)) shouldBe notSent
        client.post(link, objectBytes, "cd".repeat(32)) shouldBe notSent
    }

    @Test
    fun `production client does not follow redirects or retry the post`() {
        val http = newAdmissionHttpClient()
        http.followRedirects shouldBe false
        http.followSslRedirects shouldBe false
        http.retryOnConnectionFailure shouldBe false
    }

    private fun post(code: Int, body: String): AdmissionPostResult {
        val factory = scripted(body = body, code = code)
        val result = AdmissionDoorClient(factory).post(link, objectBytes, signature)
        factory.requests.size shouldBe 1
        return result
    }

    private fun scripted(body: String, code: Int = 200) = ScriptedFactory(body = body, code = code)

    private class ScriptedFactory(
        private val body: String = "",
        private val code: Int = 200,
        private val failure: IOException? = null,
    ) : Call.Factory {
        val requests = mutableListOf<Request>()

        override fun newCall(request: Request): Call {
            requests += request
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody(JSON))
                .build()
            return ScriptedCall(request = request, response = response, failure = failure)
        }
    }

    private class ScriptedCall(
        private val request: Request,
        private val response: Response,
        private val failure: IOException?,
    ) : Call {
        override fun request(): Request = request

        override fun execute(): Response {
            failure?.let { throw it }
            return response
        }

        override fun enqueue(responseCallback: Callback) = error("unused")

        override fun cancel() = Unit

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = false

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = this
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
