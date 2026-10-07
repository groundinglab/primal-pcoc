package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.net.SocketTimeoutException
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

class AdmissionSiteClientTest {

    private val hash = "ab".repeat(32)
    private val signature = "cd".repeat(64)
    private val objectBytes = byteArrayOf(0xfb.toByte(), 1, 2)

    @Test
    fun `posts only the two fields to the exact https url`() {
        val exact = "https://service.example/door?h=$hash"
        val acceptBody = """{"decision":"accept","verifier_ran":true,"result":"ok","measurements":{"n":1}}"""
        val factory = scripted(body = acceptBody)
        val client = AdmissionSiteClient(factory)

        val result = client.post(exactUrl = exact, objectBytes = objectBytes, signatureHex = signature)

        result shouldBe AdmissionPostResult.Delivered
        factory.requests.single().url.toString() shouldBe exact
        val recorded = factory.requests.single()
        recorded.method shouldBe "POST"
        recorded.body!!.contentType().toString() shouldContain "application/json"
        recorded.headers.names().any { it.equals("Authorization", ignoreCase = true) } shouldBe false
        val body = Buffer().also { recorded.body!!.writeTo(it) }.readUtf8()
        body.contains(""""object_b64"""") shouldBe true
        body.contains(""""signature"""") shouldBe true
        body.contains(hash) shouldBe false
        body.contains("nsec") shouldBe false
        body.contains("challenge_id") shouldBe false
        body.contains("sid") shouldBe false
        body.contains("verifier_ran") shouldBe false
    }

    @Test
    fun `a delivered post is not a policy verdict`() {
        listOf(
            """{"decision":"accept","verifier_ran":true}""",
            """{"decision":"deny","verifier_ran":true,"measurements":[1]}""",
            """{"decision":"deny","phase":"fast","reasons":["B9 shape"]}""",
            """{"decision":"deny","verifier_ran":false}""",
            """{"decision":"provisional","phase":"fast","seq":1}""",
            """{"decision":"checking"}""",
            """{"status":"revoke"}""",
            "",
            "not-json",
            "{}",
            """{"decision":"maybe"}""",
            """{"decision":"Accept"}""",
        ).forEach { body ->
            val client = AdmissionSiteClient(scripted(body = body))
            client.post(exactUrl = serviceUrl(), objectBytes = objectBytes, signatureHex = signature) shouldBe
                AdmissionPostResult.Delivered
        }
    }

    @Test
    fun `a provisional 2xx on the live sign-in url is delivered`() {
        val exact = "https://pcoc.app/sign-in/?h=$hash"
        val body = """{"decision":"provisional","phase":"fast","seq":4,"submission_sha256":"$hash"}"""
        val factory = scripted(code = 200, body = body)
        val client = AdmissionSiteClient(factory)

        client.post(exactUrl = exact, objectBytes = objectBytes, signatureHex = signature) shouldBe
            AdmissionPostResult.Delivered
        factory.requests.single().url.toString() shouldBe exact
    }

    @Test
    fun `bad signature is an error and not a refusal`() {
        listOf(
            403 to """{"error":{"code":"bad_signature","message":"the signature does not verify"}}""",
            200 to """{"error":"bad_signature"}""",
            400 to """{"decision":"deny","reason":"bad_signature"}""",
        ).forEach { (code, body) ->
            val client = AdmissionSiteClient(scripted(code = code, body = body))
            client.post(exactUrl = serviceUrl(), objectBytes = objectBytes, signatureHex = signature) shouldBe
                AdmissionPostResult.BadSignature
        }
    }

    @Test
    fun `a non 2xx body that is not bad_signature is a delivery failure`() {
        val body = """{"decision":"deny","error":{"code":"bad_gateway","message":"the admission gate did not answer"}}"""
        val client = AdmissionSiteClient(scripted(code = 502, body = body))

        client.post(exactUrl = serviceUrl(), objectBytes = objectBytes, signatureHex = signature) shouldBe
            AdmissionPostResult.Failed
    }

    @Test
    fun `a redirect is not a denial`() {
        val factory = scripted(code = 302, body = """{"decision":"deny"}""")
        val client = AdmissionSiteClient(factory)

        client.post(exactUrl = serviceUrl(), objectBytes = objectBytes, signatureHex = signature) shouldBe
            AdmissionPostResult.Failed
        factory.requests.size shouldBe 1
    }

    @Test
    fun `a followed redirect body is not a denial`() {
        val request = Request.Builder().url(serviceUrl()).build()
        val prior = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(302)
            .message("Found")
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"decision":"deny"}""".toResponseBody(JSON))
            .priorResponse(prior)
            .build()

        interpretAdmissionResponse(response) shouldBe AdmissionPostResult.Failed
    }

    @Test
    fun `timeout is a failed request`() {
        val factory = ScriptedFactory(failure = SocketTimeoutException("timed out"))
        val client = AdmissionSiteClient(factory)

        client.post(exactUrl = serviceUrl(), objectBytes = objectBytes, signatureHex = signature) shouldBe
            AdmissionPostResult.Failed
        factory.requests.size shouldBe 1
    }

    @Test
    fun `posts the live sign-in url unchanged`() {
        val hash = "01" + "ab".repeat(30) + "ff"
        val exact = "https://pcoc.app/sign-in/?h=$hash"
        val factory = scripted(body = """{"decision":"accept","verifier_ran":false}""")
        val client = AdmissionSiteClient(factory)

        client.post(exactUrl = exact, objectBytes = objectBytes, signatureHex = signature) shouldBe
            AdmissionPostResult.Delivered
        factory.requests.single().url.toString() shouldBe exact
    }

    @Test
    fun `a caller that throws is a failed request`() {
        val client = AdmissionSiteClient(Call.Factory { throw IllegalStateException("closed") })

        client.post(exactUrl = serviceUrl(), objectBytes = objectBytes, signatureHex = signature) shouldBe
            AdmissionPostResult.Failed
    }

    @Test
    fun `the phone does not post the gate, a bare hash, or http`() {
        val client = AdmissionSiteClient(Call.Factory { error("posted") })
        val gate = "https://admission.pcoc.app/v1/sign-in?h=$hash"

        client.post(gate, objectBytes, signature) shouldBe AdmissionPostResult.NotSent
        client.post(hash, objectBytes, signature) shouldBe AdmissionPostResult.NotSent
        client.post("http://service.example/door?h=$hash", objectBytes, signature) shouldBe
            AdmissionPostResult.NotSent
    }

    @Test
    fun `production client does not follow redirects or retry the post`() {
        val http = newAdmissionHttpClient()
        http.followRedirects shouldBe false
        http.followSslRedirects shouldBe false
        http.retryOnConnectionFailure shouldBe false
    }

    private fun serviceUrl() = "https://service.example/door?h=$hash"

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
