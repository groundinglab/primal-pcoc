package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import net.primal.android.core.compose.PrimalTopLevelDestination
import net.primal.android.core.compose.mainBarDestinations
import net.primal.android.pcoc.PcocDoor
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
    fun `center of the main five is sign in with the qr scanner icon`() {
        val bar = mainBarDestinations
        bar.size shouldBe 5
        bar[bar.size / 2] shouldBe PrimalTopLevelDestination.SignIn
        bar.contains(PrimalTopLevelDestination.Wallet) shouldBe false

        val source = sourceFile("src/main/kotlin/net/primal/android/core/compose/PrimalNavigationBar.kt")
        source.contains("mainBarDestinations") shouldBe true
        source.contains("PrimalTopLevelDestination.entries.forEach") shouldBe false
        source.contains("PrimalTopLevelDestination.entries.size") shouldBe false
        val signInIcon = Regex("""PrimalTopLevelDestination\.SignIn -> PrimalIcons\.(\w+)""")
            .find(source)
            ?.groupValues
            ?.get(1)
        signInIcon shouldBe "QrCodeScanner"
        listOf("Key", "Bolt", "Zap", "Lightning").forEach { signInIcon.orEmpty().shouldNotContain(it) }

        val icon = sourceFile("src/main/kotlin/net/primal/android/core/compose/icons/primaliconpack/QrCodeScanner.kt")
        icon shouldContain "get() = Icons.Outlined.QrCodeScanner"
    }

    @Test
    fun `pcoc drawer tile is the hiking icon and stays apart from remote login`() {
        val source = sourceFile("src/main/kotlin/net/primal/android/drawer/PrimalDrawer.kt")
        val tileIcon = Regex("""DrawerScreenDestination\.Pcoc -> PrimalIcons\.(\w+)""")
            .find(source)
            ?.groupValues
            ?.get(1)
        tileIcon shouldBe "Hiking"
        Regex("""DrawerScreenDestination\.RemoteLogin -> PrimalIcons\.(\w+)""")
            .find(source)
            ?.groupValues
            ?.get(1) shouldBe "RemoteLogin"
        stringValue(stringsXml(), "drawer_destination_pcoc") shouldBe "PCOC"

        val icon = sourceFile("src/main/kotlin/net/primal/android/core/compose/icons/primaliconpack/Hiking.kt")
        icon shouldContain "get() = Icons.Outlined.Hiking"
    }

    @Test
    fun `pcoc menu is import, create, verify, sign in`() {
        PcocDoor.entries shouldBe listOf(PcocDoor.Enroll, PcocDoor.Create, PcocDoor.Verify, PcocDoor.Admit)
        val xml = stringsXml()
        stringValue(xml, "pcoc_enroll_title") shouldBe "Import PCOC"
        stringValue(xml, "pcoc_create_title") shouldBe "Create new PCOC"
        stringValue(xml, "pcoc_verify_title") shouldBe "Verify"
        stringValue(xml, "pcoc_admit_title") shouldBe "Sign in"
        xml.contains("Enroll your PCOC") shouldBe false
        xml.contains("enrolls automatically") shouldBe false
        stringValue(xml, "pcoc_admit_need_pcoc") shouldContain "Import PCOC"
    }

    @Test
    fun `create new pcoc offers start hike on the live header source`() {
        val xml = stringsXml()
        xml.contains("Coming soon") shouldBe false
        xml.contains("coming soon") shouldBe false
        stringValue(xml, "pcoc_create_start") shouldBe "Start hike"
        stringValue(xml, "pcoc_create_idle_title") shouldBe "New hike"
        val body = stringValue(xml, "pcoc_create_body")
        listOf(
            "https://bari.groundinglab.io",
            "public key",
            "writes no object",
            "live header source",
            "dials Bitcoin",
        ).forEach {
            body shouldContain it
        }
        body.contains("nsec") shouldBe false
        val screen = sourceFile("src/main/kotlin/net/primal/android/pcoc/create/CreatePcocScreen.kt")
        screen.contains("CreatePcocViewModel") shouldBe true
        screen.contains("Start") shouldBe true
    }

    @Test
    fun `acknowledgement sheet copy is the locked sentences`() {
        val xml = stringsXml().replace("\\'", "'")
        stringValue(xml, "pcoc_admit_submitted_title") shouldBe "Sign-in submitted"
        stringValue(xml, "pcoc_admit_submitted_body") shouldBe "Check the site you're signing in to."
        stringValue(xml, "pcoc_admit_could_not_submit_title") shouldBe "Couldn't submit"
        stringValue(xml, "pcoc_admit_reason_bad_signature") shouldBe "bad signature"
        stringValue(xml, "pcoc_admit_reason_malformed") shouldBe "malformed"
        stringValue(xml, "pcoc_admit_origin_label") shouldBe "Signing in to"
        stringValue(xml, "pcoc_admit_close") shouldBe "Close"
    }

    @Test
    fun `sign in step notices are plain and there is no service decision or poll`() {
        val xml = stringsXml()
        listOf("pcoc_admit_confirm_title", "pcoc_admit_sign_in", "pcoc_admit_ready", "pcoc_admit_failed")
            .forEach { xml.contains("name=\"$it\"") shouldBe false }
        listOf("verifier_ran", "The service said", "decision:").forEach { xml.contains(it) shouldBe false }

        val admit = File(moduleDir(), "src/main/kotlin/net/primal/android/pcoc/admit")
        val sources = admit.listFiles().orEmpty().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("ConfirmActionAlertDialog", "verifier_ran", "\"decision\"", "latest.json", "/log/", "provisional")
            .forEach { sources.contains(it) shouldBe false }
        sources.contains("delay(") shouldBe false
        val notices = sourceFile("src/main/kotlin/net/primal/android/pcoc/admit/SignInNotices.kt")
        listOf(
            "Link recognised.",
            "Not a sign-in link.",
            "Signed.",
            "Signing failed.",
            "Sent to admission.groundinglab.io.",
            "Accepted.",
            "Refused.",
            "Expired.",
            "PCOC .cbor DM received.",
            "Failed to parse the PCOC .cbor DM.",
        ).forEach { notices.contains(it) shouldBe true }
    }

    @Test
    fun `groundinglab settings door is removed`() {
        File(moduleDir(), "src/main/kotlin/net/primal/android/settings/groundinglab").exists() shouldBe false
        sourceFile("src/main/kotlin/net/primal/android/navigation/SettingsNavigation.kt")
            .contains("groundinglab") shouldBe false
        stringsXml().contains("settings_groundinglab") shouldBe false
    }

    @Test
    fun `user facing admission entry and welcome copy`() {
        val xml = stringsXml()
        xml.contains("Request service admission") shouldBe false
        stringValue(xml, "primary_destination_sign_in_label") shouldBe "Sign in"
        stringValue(xml, "pcoc_admit_welcome_cta") shouldBe "Sign in"
        stringValue(xml, "pcoc_admit_welcome_skip").replace("\\'", "'") shouldBe "Don't show again"
        stringValue(xml, "pcoc_admit_paste") shouldBe "Paste code"
        stringValue(xml, "pcoc_admit_problem_not_link") shouldBe "That is not a sign-in link. Nothing was sent."
        stringValue(xml, "pcoc_admit_problem_retired") shouldContain "older sign-in code"

        stringValue(xml, "pcoc_admit_welcome_need").let {
            it shouldContain "PCOC"
            it shouldContain "import"
        }
        stringValue(xml, "pcoc_admit_welcome_code") shouldContain "Scan"
        stringValue(xml, "pcoc_admit_welcome_cost") shouldContain "cost-backed credential"
    }

    @Test
    fun `version name is 3_5_46`() {
        val gradle = sourceFile("build.gradle.kts")
        gradle.contains("val appVersionName = \"3.5.46\"") shouldBe true
        gradle.contains("val appVersionCode = 2743") shouldBe true
    }

    @Test
    fun `sign in app link is verified on the apex path`() {
        val manifest = sourceFile("src/main/AndroidManifest.xml")
        val filter = Regex(
            """<intent-filter\s+android:autoVerify="true"\s+android:label="Sign in">[\s\S]*?</intent-filter>""",
        ).find(manifest)?.value
        filter?.contains("""android:host="groundinglab.io"""") shouldBe true
        filter?.contains("""android:path="/sign-in/"""") shouldBe true
        filter?.contains("""android:scheme="https"""") shouldBe true
        manifest.contains("""android:host="pcoc.app"""") shouldBe false
        manifest.contains("""android:host="www.groundinglab.io"""") shouldBe false
        sourceFile("src/main/kotlin/net/primal/android/navigation/PcocNavigation.kt")
            .contains("uriPattern = SIGN_IN_APP_LINK") shouldBe true
    }

    private fun stringsXml(): String = sourceFile("src/main/res/values/strings.xml")

    private fun moduleDir(): File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }

    private fun sourceFile(modulePath: String): String = File(moduleDir(), modulePath).readText()

    private fun stringValue(xml: String, name: String): String =
        Regex("""name="$name">([^<]*)</string>""")
            .find(xml)
            ?.groupValues
            ?.get(1)
            .orEmpty()
}
