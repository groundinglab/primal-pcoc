package net.primal.android.pcoc.admit

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Test

/**
 * The same live wire mint as [SignInLinkTest], 2026-10-12,
 * from https://groundinglab.io/sign-in/wire.mjs.
 */
class SignInNoticesTest {

    private val liveWireMint =
        "https://groundinglab.io/sign-in/?v=1" +
            "&nonce=a7e1895fcbdf039dda21db9fa1c01e51425b75cc8b39bfc372e7de576815aef9" +
            "&exp=1791780671"

    @Test
    fun `scan sign submit gate and dm map to one sentence each`() {
        SIGN_IN_LOG_TAG shouldBe "SignIn"

        val recognised = SignInNotices.scan(ready = true)
        recognised.userText shouldBe "Link recognised."
        recognised.logLine shouldBe recognised.userText

        SignInNotices.scan(ready = false, why = SignInLinks.rejectWhy(www())).userText shouldBe
            "Not a sign-in link. The host is www."
        SignInNotices.scan(ready = false, why = SignInLinks.rejectWhy(http())).userText shouldBe
            "Not a sign-in link. It is not https."
        SignInNotices.scan(ready = false, why = SignInLinks.rejectWhy(pcocApp())).userText shouldBe
            "Not a sign-in link. pcoc.app is retired."
        SignInNotices.scan(ready = false, why = SignInLinks.rejectWhy("$liveWireMint&x=1")).userText shouldBe
            "Not a sign-in link. It has an extra parameter."
        SignInNotices.scan(ready = false, why = SignInLinks.rejectWhy(retired())).userText shouldBe
            "Not a sign-in link. It is the older sign-in code."

        SignInNotices.sign(ok = true).userText shouldBe "Signed."
        SignInNotices.sign(ok = false).userText shouldBe "Signing failed."
        SignInNotices.sign(ok = false).logLine shouldBe "Signing failed."

        SignInNotices.submit().userText shouldBe "Sent to admission.groundinglab.io."
        SignInNotices.submit(DeliveryFailure.NoConnection).userText shouldBe
            "Could not reach admission.groundinglab.io."
        SignInNotices.submit(DeliveryFailure.TimedOut).userText shouldBe
            "Timed out reaching admission.groundinglab.io."
        SignInNotices.submit(DeliveryFailure.NotSent).userText shouldBe "The sign-in was not sent."
        SignInNotices.submit(DeliveryFailure.UnexpectedResponse, httpCode = 503).userText shouldBe
            "admission.groundinglab.io returned HTTP 503."
        SignInNotices.submit(DeliveryFailure.Redirected, httpCode = 302).userText shouldBe
            "admission.groundinglab.io returned HTTP 302."
        SignInNotices.submit(DeliveryFailure.UnexpectedResponse, httpCode = null).userText shouldBe
            "admission.groundinglab.io returned an HTTP error."

        SignInNotices.gate("accepted")?.userText shouldBe "Accepted."
        SignInNotices.gate("expired")?.userText shouldBe "Expired."
        SignInNotices.gate("bad_signature")?.userText shouldBe "Refused. bad signature."
        SignInNotices.gate("malformed")?.userText shouldBe "Refused. malformed."
        SignInNotices.gate("too_short")?.userText shouldBe "Refused. too short."
        SignInNotices.gate("rejected")?.userText shouldBe "Refused. rejected."
        SignInNotices.gate("earlier_version")?.userText shouldBe "Refused. earlier version."
        SignInNotices.gate("too_few_waypoints_anchored")?.userText shouldBe
            "Refused. too few waypoints anchored."
        SignInNotices.gate("pending").shouldBeNull()
        SignInNotices.gate("").shouldBeNull()
        SignInNotices.gate("nope").shouldBeNull()
        SignInNotices.gate("accepted")?.logLine shouldBe "Accepted."

        SignInNotices.dm(parsed = true).userText shouldBe "PCOC .cbor DM received."
        SignInNotices.dm(parsed = false).userText shouldBe "Failed to parse the PCOC .cbor DM."
        SignInNotices.dm(parsed = true).logLine shouldBe SignInNotices.dm(parsed = true).userText
    }

    @Test
    fun `the trail names sign then submit then a gate refusal`() {
        SignInNotices.trail(AdmissionSubmitOutcome.Submitted).map { it.userText } shouldBe listOf(
            "Signed.",
            "Sent to admission.groundinglab.io.",
        )
        SignInNotices.trail(AdmissionSubmitOutcome.SigningFailed).map { it.userText } shouldBe listOf(
            "Signing failed.",
        )
        SignInNotices.trail(AdmissionSubmitOutcome.BadSignature).map { it.userText } shouldBe listOf(
            "Signed.",
            "Sent to admission.groundinglab.io.",
            "Refused. bad signature.",
        )
        SignInNotices.trail(
            AdmissionSubmitOutcome.DeliveryFailed(DeliveryFailure.NoConnection),
        ).map { it.userText } shouldBe listOf(
            "Signed.",
            "Could not reach admission.groundinglab.io.",
        )
        SignInNotices.trail(
            AdmissionSubmitOutcome.DeliveryFailed(
                failure = DeliveryFailure.UnexpectedResponse,
                httpCode = 400,
            ),
        ).map { it.userText } shouldBe listOf(
            "Signed.",
            "admission.groundinglab.io returned HTTP 400.",
        )
        SignInNotices.trail(AdmissionSubmitOutcome.NoObject) shouldBe emptyList()
        SignInNotices.trail(AdmissionSubmitOutcome.WrongLogin) shouldBe emptyList()
    }

    @Test
    fun `the DM notification title is the received sentence`() {
        val xml = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
        val title = Regex("""name="pcoc_dm_title">([^<]*)</string>""")
            .find(xml)
            ?.groupValues
            ?.get(1)
        title shouldBe SignInNotices.dm(parsed = true).userText
    }

    private fun www() = liveWireMint.replace("https://groundinglab.io", "https://www.groundinglab.io")

    private fun http() = liveWireMint.replace("https://", "http://")

    private fun pcocApp() = liveWireMint.replace("groundinglab.io", "pcoc.app")

    private fun retired() =
        "https://groundinglab.io/sign-in/?h=a7e1895fcbdf039dda21db9fa1c01e51425b75cc8b39bfc372e7de576815aef9"
}
