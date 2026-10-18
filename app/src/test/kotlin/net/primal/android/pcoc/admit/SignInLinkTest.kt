package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import net.primal.android.scanner.domain.QrCodeDataType
import org.junit.Test

class SignInLinkTest {

    @Test
    fun `app-open path maps to the locked prefix by an exact prefix swap`() {
        val query = "?v=1&nonce=$nonce&exp=1893456000"
        appOpenToLockedLink("$SIGN_IN_APP_OPEN$query") shouldBe canonical
        appOpenToLockedLink(SIGN_IN_APP_OPEN) shouldBe SIGN_IN_APP_LINK
        appOpenToLockedLink("https://groundinglab.io/sign-in/app") shouldBe SIGN_IN_APP_LINK
        appOpenToLockedLink(canonical) shouldBe canonical
        appOpenToLockedLink("https://primal.net/home") shouldBe "https://primal.net/home"
        appOpenToLockedLink("$SIGN_IN_APP_OPEN?") shouldBe "$SIGN_IN_APP_LINK?"
        SignInLinks.parse("$SIGN_IN_APP_LINK?") shouldBe SignInLinkParse.NotASignInLink
        // The app path itself is never a §15.1 link.
        SignInLinks.parse("$SIGN_IN_APP_OPEN$query") shouldBe SignInLinkParse.NotASignInLink
    }

    private val nonce = "0123456789abcdef".repeat(4)
    private val canonical = "https://groundinglab.io/sign-in/?v=1&nonce=$nonce&exp=1893456000"

    /**
     * Live mint, 2026-10-12.
     * Source: ./live-wire.mjs, fetched from https://groundinglab.io/sign-in/wire.mjs.
     * mintLink(drawNonce(crypto), Date.now()) at 2026-10-12T04:49:11.458Z produced [liveWireMint].
     * Headless Chromium opened https://groundinglab.io/sign-in/, clicked Sign in with PCOC,
     * and the payload text matched the QR data-payload: [livePageMint].
     */
    private val liveWireMint =
        "https://groundinglab.io/sign-in/?v=1" +
            "&nonce=a7e1895fcbdf039dda21db9fa1c01e51425b75cc8b39bfc372e7de576815aef9" +
            "&exp=1791780671"
    private val livePageMint =
        "https://groundinglab.io/sign-in/?v=1" +
            "&nonce=7c30e62d597a25799e272a093cad56c1a01aef211aa632f0064136ee54b6cbca" +
            "&exp=1791780684"

    @Test
    fun `a live minted object-free link parses and its derivatives do not`() {
        val wire = SignInLinks.parse(liveWireMint) as SignInLinkParse.Ready
        wire.link.text shouldBe liveWireMint
        wire.link.nonceHex shouldBe "a7e1895fcbdf039dda21db9fa1c01e51425b75cc8b39bfc372e7de576815aef9"
        wire.link.exp shouldBe 1_791_780_671uL
        wire.link.text.contains("object=") shouldBe false

        val page = SignInLinks.parse(livePageMint) as SignInLinkParse.Ready
        page.link.text shouldBe livePageMint
        page.link.nonceHex shouldBe "7c30e62d597a25799e272a093cad56c1a01aef211aa632f0064136ee54b6cbca"
        page.link.exp shouldBe 1_791_780_684uL

        SignInLinks.parse(liveWireMint.replace("https://groundinglab.io", "https://www.groundinglab.io")) shouldBe
            SignInLinkParse.NotASignInLink
        SignInLinks.parse(liveWireMint.replace("https://", "http://")) shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse(liveWireMint.replace("groundinglab.io", "pcoc.app")) shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse("$liveWireMint&x=1") shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse(
            "https://groundinglab.io/sign-in/?h=a7e1895fcbdf039dda21db9fa1c01e51425b75cc8b39bfc372e7de576815aef9",
        ) shouldBe SignInLinkParse.RetiredCode

        signInLinkExpired(exp = 1_791_780_671uL, nowMs = 1_791_780_675_000L) shouldBe false
        signInLinkExpired(exp = 1_791_780_671uL, nowMs = 1_791_780_676_000L) shouldBe true
        signInLinkExpired(exp = ULong.MAX_VALUE, nowMs = 0L) shouldBe false
    }

    @Test
    fun `scan and paste share one grammar and keep the exact link text`() {
        val ready = SignInLinks.parse("  $canonical\r\n") as SignInLinkParse.Ready
        ready.link.text shouldBe canonical
        ready.link.origin shouldBe "https://groundinglab.io"
        ready.link.nonceHex shouldBe nonce
        ready.link.exp shouldBe 1_893_456_000uL
        ready.link.text.contains("object=") shouldBe false
    }

    @Test
    fun `the retired h code is its own stop and never a ready link`() {
        val hash = "01" + "ab".repeat(30) + "ff"
        SignInLinks.parse("https://groundinglab.io/sign-in/?h=$hash") shouldBe SignInLinkParse.RetiredCode
        SignInLinks.parse("https://groundinglab.io/sign-in/?h=${hash.uppercase()}") shouldBe
            SignInLinkParse.RetiredCode
        SignInLinks.parse(hash) shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse("https://service.example/door?h=$hash") shouldBe SignInLinkParse.NotASignInLink
    }

    @Test
    fun `exp is the shortest decimal u64`() {
        fun withExp(exp: String) = canonical.replace("exp=1893456000", "exp=$exp")
        (SignInLinks.parse(withExp("0")) as SignInLinkParse.Ready).link.exp shouldBe 0uL
        (SignInLinks.parse(withExp("18446744073709551615")) as SignInLinkParse.Ready).link.exp shouldBe ULong.MAX_VALUE
        SignInLinks.parse(withExp("18446744073709551616")) shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse(withExp("99999999999999999999")) shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse(withExp("00")) shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse(withExp("-1")) shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse(withExp("1e9")) shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse("$canonical&object=${"aa".repeat(32)}") shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse("$canonical&x=1") shouldBe SignInLinkParse.NotASignInLink
    }

    @Test
    fun `only ascii whitespace is trimmed`() {
        (SignInLinks.parse("\u000C\t $canonical \n") is SignInLinkParse.Ready) shouldBe true
        SignInLinks.parse("\u00A0$canonical") shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse("$canonical\u2028") shouldBe SignInLinkParse.NotASignInLink
        SignInLinks.parse("\u000B$canonical") shouldBe SignInLinkParse.NotASignInLink
    }

    @Test
    fun `the general scanner leaves sign-in links and the retired code alone`() {
        val hash = "ab".repeat(32)
        QrCodeDataType.from(canonical) shouldBe QrCodeDataType.PCOC_SERVICE_URL
        QrCodeDataType.from("https://groundinglab.io/sign-in/?h=$hash") shouldBe QrCodeDataType.PCOC_SERVICE_URL
        (QrCodeDataType.from("https://service.example/door?h=$hash") == QrCodeDataType.PCOC_SERVICE_URL) shouldBe false
    }

    @Test
    fun `www http ports fragments and any pcoc app link are refused`() {
        val hash = "ab".repeat(32)
        listOf(
            canonical.replace("https://", "http://"),
            canonical.replace("https://groundinglab.io", "https://www.groundinglab.io"),
            canonical.replace("groundinglab.io", "groundinglab.io:443"),
            canonical.replace("groundinglab.io", "groundinglab.io:8443"),
            "https://user@groundinglab.io/sign-in/?v=1&nonce=$nonce&exp=1893456000",
            "$canonical#x",
            canonical.replace("/sign-in/", "/sign-in"),
            "https://pcoc.app/sign-in/?v=1&nonce=$nonce&exp=1893456000",
            "https://www.pcoc.app/sign-in/?v=1&nonce=$nonce&exp=1893456000",
            "http://pcoc.app/sign-in/?v=1&nonce=$nonce&exp=1893456000",
            "https://pcoc.app/sign-in/?h=$hash",
            "https://admission.pcoc.app/v1/sign-in",
            "https://admission.groundinglab.io/v1/sign-in",
        ).forEach { SignInLinks.parse(it) shouldBe SignInLinkParse.NotASignInLink }
    }
}
