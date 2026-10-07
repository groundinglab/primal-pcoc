package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import net.primal.android.core.compose.PrimalTopLevelDestination
import net.primal.android.core.compose.mainBarDestinations
import org.junit.Test

class SignInHubTest {

    @Test
    fun `first open shows welcome and continuing without the checkbox shows it again`() {
        val gate = SignInWelcomeGate()
        gate.onOpen() shouldBe SignInWelcomeStep.Welcome
        gate.enterSignIn() shouldBe SignInWelcomeStep.Camera
        SignInWelcomeGate(skipWelcome = gate.skipWelcome).onOpen() shouldBe SignInWelcomeStep.Welcome
    }

    @Test
    fun `dont show again skips later opens and stays set after entering sign in`() {
        val gate = SignInWelcomeGate()
        gate.onOpen() shouldBe SignInWelcomeStep.Welcome
        gate.setDontShowAgain(true)
        gate.enterSignIn() shouldBe SignInWelcomeStep.Camera
        val later = SignInWelcomeGate(skipWelcome = gate.skipWelcome)
        later.onOpen() shouldBe SignInWelcomeStep.Camera
        later.skipWelcome shouldBe true
    }

    @Test
    fun `clearing dont show again shows the welcome on the next open`() {
        val gate = SignInWelcomeGate(skipWelcome = true)
        gate.onOpen() shouldBe SignInWelcomeStep.Camera
        gate.setDontShowAgain(false)
        SignInWelcomeGate(skipWelcome = gate.skipWelcome).onOpen() shouldBe SignInWelcomeStep.Welcome
    }

    @Test
    fun `center of the main five is sign in and wallet is not in that slot`() {
        val bar = mainBarDestinations
        bar.size shouldBe 5
        bar[bar.size / 2] shouldBe PrimalTopLevelDestination.SignIn
        bar.contains(PrimalTopLevelDestination.Wallet) shouldBe false

        val source = sourceFile(
            "src/main/kotlin/net/primal/android/core/compose/PrimalNavigationBar.kt",
            "app/src/main/kotlin/net/primal/android/core/compose/PrimalNavigationBar.kt",
        )
        source.contains("mainBarDestinations") shouldBe true
        source.contains("PrimalTopLevelDestination.entries.forEach") shouldBe false
        source.contains("PrimalTopLevelDestination.entries.size") shouldBe false
        val signInIcon = Regex("""PrimalTopLevelDestination\.SignIn -> PrimalIcons\.(\w+)""")
            .find(source)
            ?.groupValues
            ?.get(1)
        signInIcon shouldBe "Key"
        signInIcon.orEmpty().shouldNotContain("Bolt")
        signInIcon.orEmpty().shouldNotContain("Zap")
        signInIcon.orEmpty().shouldNotContain("Lightning")
    }

    @Test
    fun `user facing admission entry and welcome copy`() {
        val xml = stringsXml()
        xml.contains("Request service admission") shouldBe false
        stringValue(xml, "pcoc_admit_title") shouldBe "Sign in"
        stringValue(xml, "primary_destination_sign_in_label") shouldBe "Sign in"
        stringValue(xml, "pcoc_admit_welcome_cta") shouldBe "Sign in"
        stringValue(xml, "pcoc_admit_welcome_skip").replace("\\'", "'") shouldBe "Don't show again"
        stringValue(xml, "pcoc_admit_paste") shouldBe "Paste code"
        stringValue(xml, "pcoc_admit_paste_error") shouldBe "That is not a sign-in code."

        val need = stringValue(xml, "pcoc_admit_welcome_need")
        need.shouldContain("PCOC")
        need.shouldContain("Produce")
        need.shouldContain("import")

        val code = stringValue(xml, "pcoc_admit_welcome_code")
        code.shouldContain("Scan")
        code.shouldContain("paste")

        val cost = stringValue(xml, "pcoc_admit_welcome_cost")
        cost.shouldContain("cost-backed credential")
    }

    @Test
    fun `version name is 3_5_39`() {
        val gradle = sourceFile("build.gradle.kts", "app/build.gradle.kts")
        gradle.contains("val appVersionName = \"3.5.39\"") shouldBe true
        gradle.contains("val appVersionCode = 2736") shouldBe true
    }

    private fun stringsXml(): String =
        sourceFile(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )

    private fun sourceFile(modulePath: String, repoPath: String): String =
        listOf(File(modulePath), File(repoPath)).first { it.isFile }.readText()

    private fun stringValue(xml: String, name: String): String =
        Regex("""name="$name">([^<]*)</string>""")
            .find(xml)
            ?.groupValues
            ?.get(1)
            .orEmpty()
}
