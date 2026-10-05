package net.primal.android.settings.groundinglab

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test

class AdmissionGateClientTest {

    @Test
    fun signIn_postsOnlyObjectAndSignatureAndReadsDecision() {
        val gate = scriptedGate("""{"decision":"accept","note":"ignored"}""")
        val client = client(gate)

        val result = client.signIn(objectBytes = byteArrayOf(1, 2, 3), signatureHex = "ab".repeat(64))

        result shouldBe AdmissionSignInResult.Decision("accept")
        val recorded = gate.requests.single()
        recorded.method shouldBe "POST"
        recorded.url.encodedPath shouldBe "/v1/sign-in"
        recorded.body!!.contentType().toString() shouldBe "application/json"
        val body = recorded.bodyUtf8()
        Json.parseToJsonElement(body).jsonObject.keys shouldBe setOf("object_b64", "signature")
        body.shouldContain(""""signature":"${"ab".repeat(64)}"""")
        body.contains("height") shouldBe false
        body.contains("challenge_id") shouldBe false
    }

    @Test
    fun signIn_denyIsTheDecision() {
        val gate = scriptedGate("""{"decision":"deny"}""")
        client(gate).signIn(byteArrayOf(9), "cd".repeat(64)) shouldBe
            AdmissionSignInResult.Decision("deny")
    }

    @Test
    fun signIn_emptyBodyAndMissingDecisionFail() {
        val gate = scriptedGate("", """{"ok":true}""")
        val httpClient = client(gate)
        httpClient.signIn(byteArrayOf(1), "aa".repeat(64)) shouldBe AdmissionSignInResult.EmptyBody
        httpClient.signIn(byteArrayOf(1), "aa".repeat(64)) shouldBe AdmissionSignInResult.MissingDecision
    }

    @Test
    fun signIn_transportFailureWhenTheGateIsDown() {
        val http = OkHttpClient.Builder()
            .addInterceptor { throw IOException("gate down") }
            .build()
        val client = AdmissionGateClient(http = http, signInUrl = SIGN_IN_URL)
        client.signIn(byteArrayOf(1), "aa".repeat(64)) shouldBe AdmissionSignInResult.TransportFailure
    }

    private fun client(gate: ScriptedGate) = AdmissionGateClient(http = gate.http, signInUrl = SIGN_IN_URL)

    private fun scriptedGate(vararg bodies: String) = ScriptedGate(bodies.toList())

    private class ScriptedGate(bodies: List<String>) : Interceptor {
        private val remaining = ArrayDeque(bodies)
        val requests = mutableListOf<Request>()
        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val body = remaining.removeFirst()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_OK)
                .message("OK")
                .body(body.toResponseBody(JSON))
                .build()
        }
    }

    private fun Request.bodyUtf8(): String {
        val buffer = Buffer()
        body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    private companion object {
        const val SIGN_IN_URL = "https://admission.pcoc.app/v1/sign-in"
        const val HTTP_OK = 200
        val JSON = "application/json".toMediaType()
    }
}
