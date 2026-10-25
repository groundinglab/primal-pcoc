package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex

/**
 * One gate signing link. [kind] is sign-in or flush (Reset).
 * Sign-in: `https://groundinglab.io/sign-in/?v=1&nonce=<64 lowercase hex>&exp=<shortest decimal u64>`.
 * Flush: `https://groundinglab.io/flush/?v=1&nonce=<64 lowercase hex>&exp=<shortest decimal u64>`.
 * `v` stays 1. Sign-in names `pcoc-gate-signin-v1`. Flush names `pcoc-gate-flush-v1`.
 * The link does not name an object, a public key, or the door. The origin is always [SIGN_IN_ORIGIN].
 * `www`, `http`, a port, a fragment, an extra parameter, the retired `?h=` form, and any `pcoc.app` link are refused.
 */
class SignInLink(
    val text: String,
    val nonceHex: String,
    val exp: ULong,
    val kind: GateLinkKind = GateLinkKind.SignIn,
) {
    val origin: String get() = SIGN_IN_ORIGIN

    fun nonce(): ByteArray = Hex.decode(nonceHex)

    override fun equals(other: Any?): Boolean = other is SignInLink && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "SignInLink(kind=$kind, exp=$exp)"
}

/** Sign-in and Reset are the same link shape. The path selects the challenge domain. */
enum class GateLinkKind {
    SignIn,
    Flush,
}

sealed class SignInLinkParse {
    data class Ready(val link: SignInLink) : SignInLinkParse()

    /** `https://groundinglab.io/sign-in/?h=` plus 64 hex. Retired by the §15.1 lock. */
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
                    kind = GateLinkKind.SignIn,
                ),
            )
            RETIRED.matches(exact) -> SignInLinkParse.RetiredCode
            else -> SignInLinkParse.NotASignInLink
        }
    }

    fun isSignInLink(raw: String): Boolean = parse(raw) is SignInLinkParse.Ready

    /** Why [parse] is not [SignInLinkParse.Ready]. Empty when the link is ready. */
    fun rejectWhy(raw: String): String {
        val exact = raw.trimAsciiWhitespace()
        val lower = exact.lowercase()
        val onOurPath = exact.startsWith(SIGN_IN_APP_LINK)
        return when {
            CANONICAL.matches(exact) -> ""
            RETIRED.matches(exact) -> "It is the older sign-in code."
            "pcoc.app" in lower -> "pcoc.app is retired."
            lower.startsWith("http://") -> "It is not https."
            lower.startsWith("https://www.groundinglab.io/") || lower == "https://www.groundinglab.io" ->
                "The host is www."
            onOurPath && '#' in exact -> "It has a fragment."
            onOurPath && exact.contains('?') -> queryWhy(exact)
            else -> "It does not match the sign-in link."
        }
    }

    private fun queryWhy(exact: String): String {
        val names = exact.substringAfter('?').substringBefore('#').split('&').map { it.substringBefore('=') }
        val allowed = setOf("v", "nonce", "exp")
        return if (names.size != allowed.size || names.any { it !in allowed }) {
            "It has an extra parameter."
        } else {
            "The nonce or expiry is not valid."
        }
    }
}

/**
 * The Gate window has closed: `now >= exp + 5` seconds.
 * A link that is still inside that skew is not expired. A far-future `exp` is not expired.
 */
internal fun signInLinkExpired(exp: ULong, nowMs: Long): Boolean {
    if (nowMs < 0L) return false
    val nowS = (nowMs / 1000L).toULong()
    val limit = if (exp > ULong.MAX_VALUE - SIGN_IN_SKEW_S) ULong.MAX_VALUE else exp + SIGN_IN_SKEW_S
    return nowS >= limit
}

/** Rust `trim_ascii` set: space, tab, LF, FF, CR. */
internal fun String.trimAsciiWhitespace(): String = trim { it in ASCII_WHITESPACE }

/** WebAuthn origin bound into `challenge_bytes`. 23 UTF-8 bytes. Not `www`, and not `pcoc.app`. */
const val SIGN_IN_ORIGIN = "https://groundinglab.io"

/** Exact app-link prefix. Trailing slash, no query. The intent filter and the nav deep link use it. */
const val SIGN_IN_APP_LINK = "$SIGN_IN_ORIGIN/sign-in/"

/** Exact Reset app-link prefix. Same origin, same query grammar, path `/flush/`. */
const val FLUSH_APP_LINK = "$SIGN_IN_ORIGIN/flush/"

private const val ASCII_WHITESPACE = " \t\n\u000C\r"
private const val SIGN_IN_SKEW_S = 5uL

private val CANONICAL = Regex(
    "^${Regex.escape(SIGN_IN_APP_LINK)}\\?v=1&nonce=([0-9a-f]{64})&exp=(0|[1-9][0-9]{0,19})$",
)

private val RETIRED = Regex(
    "^${Regex.escape(SIGN_IN_APP_LINK)}\\?h=[0-9a-fA-F]{64}$",
)

private val FLUSH = Regex(
    "^${Regex.escape(FLUSH_APP_LINK)}\\?v=1&nonce=([0-9a-f]{64})&exp=(0|[1-9][0-9]{0,19})$",
)

sealed class GateLinkParse {
    data class Ready(val link: SignInLink) : GateLinkParse()

    /** `https://groundinglab.io/sign-in/?h=` plus 64 hex. Retired by the §15.1 lock. */
    data object RetiredCode : GateLinkParse()

    data object NotALink : GateLinkParse()
}

/**
 * One grammar for scan, paste, and an opened app link.
 * Sign-in and Reset share the query. The path selects [GateLinkKind].
 */
object GateLinks {
    fun parse(raw: String): GateLinkParse {
        when (val signIn = SignInLinks.parse(raw)) {
            is SignInLinkParse.Ready -> return GateLinkParse.Ready(signIn.link)
            SignInLinkParse.RetiredCode -> return GateLinkParse.RetiredCode
            SignInLinkParse.NotASignInLink -> Unit
        }
        val exact = raw.trimAsciiWhitespace()
        val match = FLUSH.matchEntire(exact)
        val exp = match?.groupValues?.get(2)?.toULongOrNull()
        return if (match != null && exp != null) {
            GateLinkParse.Ready(
                SignInLink(
                    text = exact,
                    nonceHex = match.groupValues[1],
                    exp = exp,
                    kind = GateLinkKind.Flush,
                ),
            )
        } else {
            GateLinkParse.NotALink
        }
    }
}

/** Why a scan is not a ready sign-in or Reset link. Empty when [GateLinks.parse] is ready. */
internal fun signingLinkRejectWhy(raw: String): String {
    if (GateLinks.parse(raw) is GateLinkParse.Ready) return ""
    val exact = raw.trimAsciiWhitespace()
    if (!isFlushAttempt(exact)) return SignInLinks.rejectWhy(raw)
    val lower = exact.lowercase()
    val onFlushPath = exact.startsWith(FLUSH_APP_LINK)
    return when {
        "pcoc.app" in lower -> "pcoc.app is retired."
        lower.startsWith("http://") -> "It is not https."
        lower.startsWith("https://www.groundinglab.io/") || lower == "https://www.groundinglab.io" ->
            "The host is www."
        onFlushPath && '#' in exact -> "It has a fragment."
        onFlushPath && exact.contains('?') -> flushQueryWhy(exact)
        else -> "It does not match the sign-in link."
    }
}

internal fun isFlushAttempt(raw: String): Boolean {
    val lower = raw.trimAsciiWhitespace().lowercase()
    if ("/flush" !in lower) return false
    return "groundinglab.io" in lower || "pcoc.app" in lower
}

private fun flushQueryWhy(exact: String): String {
    val names = exact.substringAfter('?').substringBefore('#').split('&').map { it.substringBefore('=') }
    val allowed = setOf("v", "nonce", "exp")
    return if (names.size != allowed.size || names.any { it !in allowed }) {
        "It has an extra parameter."
    } else {
        "The nonce or expiry is not valid."
    }
}
