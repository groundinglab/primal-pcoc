package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex

/**
 * The SPEC §15.1 sign-in link, the same string for the QR and for paste:
 * `https://pcoc.app/sign-in/?v=1&nonce=<64 lowercase hex>&exp=<shortest decimal u64>`.
 * `v` stays 1. It names the unchanged challenge-bytes domain.
 * The link does not name an object and does not name the door. The origin is always [SIGN_IN_ORIGIN].
 */
class SignInLink(
    val text: String,
    val nonceHex: String,
    val exp: ULong,
) {
    val origin: String get() = SIGN_IN_ORIGIN

    fun nonce(): ByteArray = Hex.decode(nonceHex)

    override fun equals(other: Any?): Boolean = other is SignInLink && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "SignInLink(exp=$exp)"
}

sealed class SignInLinkParse {
    data class Ready(val link: SignInLink) : SignInLinkParse()

    /** `https://pcoc.app/sign-in/?h=` plus 64 hex. Retired by the §15.1 lock. */
    data object RetiredCode : SignInLinkParse()

    data object NotASignInLink : SignInLinkParse()
}

/** One grammar for scan and paste. ASCII whitespace is trimmed; nothing else is normalised. */
object SignInLinks {
    fun parse(raw: String): SignInLinkParse {
        val exact = raw.trimAsciiWhitespace()
        val match = CANONICAL.matchEntire(exact)
        val exp = match?.groupValues?.get(2)?.toULongOrNull()
        return when {
            match != null && exp != null -> SignInLinkParse.Ready(
                SignInLink(
                    text = exact,
                    nonceHex = match.groupValues[1],
                    exp = exp,
                ),
            )
            RETIRED.matches(exact) -> SignInLinkParse.RetiredCode
            else -> SignInLinkParse.NotASignInLink
        }
    }

    fun isSignInLink(raw: String): Boolean = parse(raw) is SignInLinkParse.Ready
}

/** Rust `trim_ascii` set: space, tab, LF, FF, CR. */
internal fun String.trimAsciiWhitespace(): String = trim { it in ASCII_WHITESPACE }

const val SIGN_IN_ORIGIN = "https://pcoc.app"

private const val ASCII_WHITESPACE = " \t\n\u000C\r"

private val CANONICAL = Regex(
    """^https://pcoc\.app/sign-in/\?v=1&nonce=([0-9a-f]{64})&exp=(0|[1-9][0-9]{0,19})$""",
)

private val RETIRED = Regex("""^https://pcoc\.app/sign-in/\?h=[0-9a-fA-F]{64}$""")
