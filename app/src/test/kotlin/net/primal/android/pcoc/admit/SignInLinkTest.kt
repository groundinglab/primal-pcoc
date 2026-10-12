package net.primal.android.pcoc.admit

import io.kotest.matchers.shouldBe
import net.primal.android.scanner.domain.QrCodeDataType
import org.junit.Test

class SignInLinkTest {

    private val nonce = "0123456789abcdef".repeat(4)
    private val canonical = "https://groundinglab.io/sign-in/?v=1&nonce=$nonce&exp=1893456000"

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
