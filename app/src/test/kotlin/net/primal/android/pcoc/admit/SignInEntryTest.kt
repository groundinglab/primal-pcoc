package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import net.primal.android.pcoc.admit.RequestAdmissionContract.CouldNotSubmitReason
import net.primal.android.pcoc.admit.RequestAdmissionContract.ScanProblem
import net.primal.android.pcoc.admit.RequestAdmissionContract.Sheet
import org.junit.Test

class SignInEntryTest {

    private val nonce = "a7e1895fcbdf039dda21db9fa1c01e51425b75cc8b39bfc372e7de576815aef9"
    private val link =
        "https://groundinglab.io/sign-in/?v=1&nonce=$nonce&exp=1791780671"

    @Test
    fun `an empty or initial camera read is not a failed scan`() {
        val empty = frames("", "   ", "\n")
        empty shouldBe null

        val initial = frames("not a sign-in link")
        initial shouldBe null

        frames("not a sign-in link", link, link) shouldBe link
        classifyLink(link, LinkSource.Entered).shouldBeInstanceOf<LinkInput.Ready>()
    }

    @Test
    fun `a repeated real value that fails to parse is the not-a-link error`() {
        frames("hello", "hello") shouldBe "hello"
        classifyLink("hello", LinkSource.Entered) shouldBe LinkInput.Rejected(
            problem = ScanProblem.NotASignInLink,
            why = "It does not match the sign-in link.",
        )

        val retired = "https://groundinglab.io/sign-in/?h=$nonce"
        frames(retired, retired) shouldBe retired
        classifyLink(retired, LinkSource.Entered) shouldBe LinkInput.Rejected(
            problem = ScanProblem.RetiredCode,
            why = "It is the older sign-in code.",
        )
    }

    @Test
    fun `a value already handed to the parser is stale`() {
        var latch = ScanLatch()
        val first = latch.take("hello")
        latch = first.latch
        first.value shouldBe null
        val second = latch.take("hello")
        latch = second.latch
        second.value shouldBe "hello"
        latch.take("hello").value shouldBe null
        latch.take("  hello  ").value shouldBe null
    }

    @Test
    fun `a blank frame does not clear a code that is still in front of the camera`() {
        var latch = ScanLatch()
        latch = latch.take(link).latch
        latch = latch.take("").latch
        latch = latch.take("   ").latch
        latch.take(link).value shouldBe link
    }

    @Test
    fun `the navigation route, a stale page, and a blank intent are not sign-in links`() {
        listOf(
            "",
            "   ",
            "android-app://androidx.navigation/pcoc_admit",
            "pcoc_admit",
            "https://primal.net/home",
            "https://primal.net/explore",
            SIGN_IN_APP_LINK,
            "https://groundinglab.io/sign-in",
            "https://groundinglab.io",
            "https://groundinglab.io/",
        ).forEach { raw ->
            classifyLink(raw, LinkSource.AppLink) shouldBe LinkInput.Ignore
        }
    }

    @Test
    fun `an opened sign-in url is parsed and a bad one is rejected`() {
        val ready = classifyLink(link, LinkSource.AppLink).shouldBeInstanceOf<LinkInput.Ready>()
        ready.link.text shouldBe link

        val www = link.replace("https://groundinglab.io", "https://www.groundinglab.io")
        classifyLink(www, LinkSource.AppLink) shouldBe LinkInput.Rejected(
            problem = ScanProblem.NotASignInLink,
            why = "The host is www.",
        )

        val pcoc = link.replace("groundinglab.io", "pcoc.app")
        classifyLink(pcoc, LinkSource.AppLink) shouldBe LinkInput.Rejected(
            problem = ScanProblem.NotASignInLink,
            why = "pcoc.app is retired.",
        )
    }

    @Test
    fun `a blank paste is ignored and a blank is not the not-a-link error`() {
        classifyLink("", LinkSource.Entered) shouldBe LinkInput.Ignore
        classifyLink(" \t\n", LinkSource.Entered) shouldBe LinkInput.Ignore
        classifyLink(link, LinkSource.Entered).shouldBeInstanceOf<LinkInput.Ready>()
    }

    @Test
    fun `a delivered sign-in returns to the screen that opened it`() {
        signInCloseTarget(Sheet.Submitted) shouldBe SignInCloseTarget.Caller
        CouldNotSubmitReason.entries.forEach { reason ->
            signInCloseTarget(Sheet.CouldNotSubmit(reason)) shouldBe SignInCloseTarget.PcocMenu
        }

        val screen = sourceFile("src/main/kotlin/net/primal/android/pcoc/admit/RequestAdmissionScreen.kt")
        screen.contains("signInCloseTarget(sheet)") shouldBe true
        screen.contains("SignInCloseTarget.Caller -> onClose") shouldBe true
        screen.contains("SignInCloseTarget.PcocMenu -> onCloseToMenu") shouldBe true

        val navigation = sourceFile("src/main/kotlin/net/primal/android/navigation/PcocNavigation.kt")
        navigation.contains("onClose = { navController.navigateUp() }") shouldBe true
        navigation.contains("onCloseToMenu = { navController.closeSignInToPcocMenu() }") shouldBe true

        val scanner = sourceFile("src/main/kotlin/net/primal/android/pcoc/admit/AdmissionQrScanner.kt")
        scanner.contains("latch.take(") shouldBe true
    }

    private fun frames(vararg raw: String): String? {
        var latch = ScanLatch()
        var value: String? = null
        raw.forEach { frame ->
            val taken = latch.take(frame)
            latch = taken.latch
            if (taken.value != null) value = taken.value
        }
        return value
    }

    private fun sourceFile(modulePath: String): String {
        val module = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        return File(module, modulePath).readText()
    }
}
