package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.IOException
import java.time.ZoneOffset
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Test

class GateDecisionLogTest {

    private val mine = "ab".repeat(32)
    private val other = "cd".repeat(32)
    private val signature = "ef".repeat(64)
    private val latest = "https://log.test/latest.json"
    private val root = "https://log.test/log/"

    @Test
    fun `the public log keeps this login and drops other keys and signatures`() {
        val lines = """
            {"event":"gate.decision","seq":1,"time_unix_s":1792830841,"decision":"refuse","public_key":"$other","signature":"$signature"}
            {"event":"gate.decision","seq":2,"time_unix_s":1792830841,"decision":"accept","public_key":"${mine.uppercase()}","signature":"$signature","origin":"https://groundinglab.io"}
            {"event":"gate.decision","seq":4,"time_unix_s":1792830900,"decision":"provisional","public_key":"$mine","reason":"still working"}
            {"event":"gate.decision","seq":3,"time_unix_s":1792830900,"decision":"return","public_key":"$mine"}
            {"event":"gate.decision","seq":5,"time_unix_s":1792830900,"decision":"nope","public_key":"$mine"}
            {"event":"gate.decision","seq":6,"time_unix_s":1792830800,"decision":"refuse","public_key":"$mine","origin":"http://groundinglab.io"}
        """.trimIndent()
        val http = routed(
            latest to ok("""{"today":"2026-10-24","policy":{"ignored":true}}"""),
            root + "2026-10-24.jsonl" to ok(lines),
        )

        val loaded = GateDecisionLog(http, latestUrl = latest, logRoot = root).load(mine)

        val ready = loaded as PreviousSigningsLoad.Ready
        ready.rows.map { formatPreviousSigning(it, ZoneOffset.UTC) } shouldBe listOf(
            "24 Oct 2026, 08:35 · Provisional",
            "24 Oct 2026, 08:35 · Return",
            "24 Oct 2026, 08:34 · Accept · https://groundinglab.io",
            "24 Oct 2026, 08:33 · Refuse",
        )
        val shown = ready.rows.joinToString("\n") { formatPreviousSigning(it, ZoneOffset.UTC) }
        shown.shouldNotContain(signature)
        shown.shouldNotContain(mine)
        shown.shouldNotContain("still working")
        shown.shouldNotContain("nsec")
        http.urls shouldBe listOf(latest, root + "2026-10-24.jsonl")
    }

    @Test
    fun `no rows for this login is an honest empty state`() {
        val none = """
            {"time_unix_s":1792830841,"decision":"accept","public_key":"$other"}
        """.trimIndent()
        load(latestBody = """{"today":"2026-10-24"}""", logBody = none, logCode = 200) shouldBe
            PreviousSigningsLoad.Empty
        load(latestCode = 404, latestBody = "", logBody = none) shouldBe PreviousSigningsLoad.Empty
        load(latestBody = """{"today":"2026-10-24"}""", logCode = 404, logBody = "") shouldBe
            PreviousSigningsLoad.Empty
        load(latestBody = """{"today":"2026-10-24"}""", logBody = "   ") shouldBe PreviousSigningsLoad.Empty

        val offline = routed()
        GateDecisionLog(offline, latestUrl = latest, logRoot = root).load("not-a-key") shouldBe
            PreviousSigningsLoad.Empty
        offline.urls shouldBe emptyList()
    }

    @Test
    fun `a log that cannot be read says it could not load`() {
        val down = RoutedHttp { throw IOException("down") }
        GateDecisionLog(down, latestUrl = latest, logRoot = root).load(mine) shouldBe
            PreviousSigningsLoad.Unavailable
        load(latestCode = 500, latestBody = """{"today":"2026-10-24"}""", logBody = "") shouldBe
            PreviousSigningsLoad.Unavailable
        load(latestBody = "<html>no</html>", logBody = "") shouldBe PreviousSigningsLoad.Unavailable
        load(latestBody = """{"today":"2026-10-24"}""", logBody = "not-json") shouldBe
            PreviousSigningsLoad.Unavailable
        load(latestBody = "{}", logBody = "") shouldBe PreviousSigningsLoad.Unavailable
    }

    private fun load(
        latestBody: String,
        logBody: String,
        latestCode: Int = 200,
        logCode: Int = 200,
    ): PreviousSigningsLoad {
        val http = routed(
            latest to response(latestCode, latestBody),
            root + "2026-10-24.jsonl" to response(logCode, logBody),
        )
        return GateDecisionLog(http, latestUrl = latest, logRoot = root).load(mine)
    }

    private fun ok(body: String): Response = response(200, body)

    private fun response(code: Int, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(latest).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody(JSON))
            .build()

    private fun routed(vararg routes: Pair<String, Response>): RoutedHttp =
        RoutedHttp { request ->
            routes.toMap().getValue(request.url.toString())
        }

    private class RoutedHttp(private val handler: (Request) -> Response) : Call.Factory {
        val urls = mutableListOf<String>()

        override fun newCall(request: Request): Call {
            urls += request.url.toString()
            return RoutedCall(request, handler)
        }
    }

    private class RoutedCall(private val request: Request, private val handler: (Request) -> Response) : Call {
        override fun request(): Request = request

        override fun execute(): Response = handler(request)

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
