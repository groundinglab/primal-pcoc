package net.primal.android.settings.groundinglab

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class AdmissionGateClientTest {

    @Test
    fun signIn_postsOnlyObjectAndSignatureAndReadsDecision() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"decision":"accept","note":"ignored"}"""))
        server.start()
        try {
            val client = client(server)
            val result = client.signIn(objectBytes = byteArrayOf(1, 2, 3), signatureHex = "ab".repeat(64))

            result shouldBe AdmissionSignInResult.Decision("accept")
            val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
            recorded.method shouldBe "POST"
            recorded.path shouldBe "/v1/sign-in"
            recorded.getHeader("Content-Type") shouldBe "application/json"
            val body = recorded.body.readUtf8()
            Json.parseToJsonElement(body).jsonObject.keys shouldBe setOf("object_b64", "signature")
            body.shouldContain(""""signature":"${"ab".repeat(64)}"""")
            body.contains("height") shouldBe false
            body.contains("challenge_id") shouldBe false
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signIn_denyIsTheDecision() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"decision":"deny"}"""))
        server.start()
        try {
            client(server).signIn(byteArrayOf(9), "cd".repeat(64)) shouldBe
                AdmissionSignInResult.Decision("deny")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signIn_emptyBodyAndMissingDecisionFail() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.start()
        try {
            val client = client(server)
            client.signIn(byteArrayOf(1), "aa".repeat(64)) shouldBe AdmissionSignInResult.EmptyBody
            client.signIn(byteArrayOf(1), "aa".repeat(64)) shouldBe AdmissionSignInResult.MissingDecision
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signIn_transportFailureWhenTheGateIsDown() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/v1/sign-in").toString()
        server.shutdown()

        val client = AdmissionGateClient(
            http = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build(),
            signInUrl = url,
        )
        client.signIn(byteArrayOf(1), "aa".repeat(64)) shouldBe AdmissionSignInResult.TransportFailure
    }

    private fun client(server: MockWebServer) =
        AdmissionGateClient(
            http = OkHttpClient(),
            signInUrl = server.url("/v1/sign-in").toString(),
        )
}
