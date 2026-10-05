package net.primal.android.pcoc.hike

import io.kotest.matchers.shouldBe
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Test

class LiveIndexClientTest {

    @Test
    fun `every index get uses the live url and the pcoc-hike user agent`() {
        val factory = ScriptedFactory(body = """{"header":{"hash":"${"ab".repeat(32)}","height":1}}""")
        val index = LiveIndex(factory)

        index.read(969_937L)

        val request = factory.requests.single()
        request.method shouldBe "GET"
        request.url.toString() shouldBe "https://index.pcoc.app/index/969937"
        request.header("User-Agent") shouldBe "pcoc-hike"
        LiveIndex.LIVE_INDEX_HOST shouldBe "index.pcoc.app"
        LiveIndex.LIVE_INDEX_URL shouldBe "https://index.pcoc.app"
        (LiveIndex.LIVE_INDEX_HOST == LiveIndex.LIVE_INDEX_URL) shouldBe false
    }

    @Test
    fun `a status other than 200 is an unsuccessful read`() {
        val factory = ScriptedFactory(code = 403, body = "no")
        LiveIndex(factory).read(1L) shouldBe null
        factory.requests.single().header("User-Agent") shouldBe "pcoc-hike"
    }

    @Test
    fun `a redirect is not followed`() {
        val factory = ScriptedFactory(code = 302, body = """{"header":{"hash":"${"ab".repeat(32)}","height":1}}""")
        LiveIndex(factory).read(1L) shouldBe null
        factory.requests.size shouldBe 1
    }

    @Test
    fun `a timeout is an unsuccessful read`() {
        val factory = ScriptedFactory(failure = IOException("timed out"))
        LiveIndex(factory).read(1L) shouldBe null
    }

    @Test
    fun `the production client does not follow redirects and limits the read to 30 seconds`() {
        val http = LiveIndex.newClient()
        http.followRedirects shouldBe false
        http.followSslRedirects shouldBe false
        http.retryOnConnectionFailure shouldBe false
        http.readTimeoutMillis shouldBe TimeUnit.SECONDS.toMillis(30L)
        http.callTimeoutMillis shouldBe TimeUnit.SECONDS.toMillis(30L)
    }

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
                .body(body.toResponseBody(null))
                .build()
            return ScriptedCall(request, response, failure)
        }
    }

    private class ScriptedCall(
        private val request: Request,
        private val response: Response,
        private val failure: IOException?,
    ) : Call {
        override fun request(): Request = request
        override fun execute(): Response = failure?.let { throw it } ?: response
        override fun enqueue(responseCallback: Callback) = Unit
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = this
    }
}
