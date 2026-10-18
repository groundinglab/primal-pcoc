package net.primal.android.pcoc.admit

import net.primal.android.pcoc.admit.RequestAdmissionContract.ScanProblem
import net.primal.android.pcoc.admit.RequestAdmissionContract.Sheet

/**
 * Where Close goes after the acknowledgement sheet.
 * A delivered sign-in pops back to the screen that opened it.
 * A sign-in that was not delivered still leaves for the PCOC menu.
 */
internal enum class SignInCloseTarget {
    Caller,
    PcocMenu,
}

internal fun signInCloseTarget(sheet: Sheet): SignInCloseTarget =
    when (sheet) {
        Sheet.Submitted -> SignInCloseTarget.Caller
        is Sheet.CouldNotSubmit -> SignInCloseTarget.PcocMenu
    }

/** Scan and paste are values the user produced. An app link is the intent that opened the screen. */
internal enum class LinkSource {
    Entered,
    AppLink,
}

internal sealed interface LinkInput {
    data object Ignore : LinkInput

    data class Ready(val link: SignInLink) : LinkInput

    data class Rejected(val problem: ScanProblem, val why: String) : LinkInput
}

/**
 * Empty text is not a scan. Navigation's `navigate(route)` stores a synthetic intent whose
 * data is the route, the intent filter also matches the bare sign-in page, and a previous
 * primal.net intent can still be on the entry. None of those is a code. An opened sign-in
 * URL that fails [SignInLinks.parse], and any non-blank value from scan or paste, is a rejection.
 */
internal fun classifyLink(raw: String, source: LinkSource): LinkInput {
    // Only an opened link is mapped from the app-open path. Scan and paste keep the one strict grammar.
    val trimmed = raw.trimAsciiWhitespace()
    val text = if (source == LinkSource.AppLink) appOpenToLockedLink(trimmed) else trimmed
    if (text.isEmpty()) return LinkInput.Ignore
    if (source == LinkSource.AppLink && !isOpenedSignInUrl(text)) return LinkInput.Ignore
    return when (val parsed = SignInLinks.parse(text)) {
        is SignInLinkParse.Ready -> LinkInput.Ready(parsed.link)
        SignInLinkParse.RetiredCode -> LinkInput.Rejected(
            problem = ScanProblem.RetiredCode,
            why = SignInLinks.rejectWhy(text),
        )
        SignInLinkParse.NotASignInLink -> LinkInput.Rejected(
            problem = ScanProblem.NotASignInLink,
            why = SignInLinks.rejectWhy(text),
        )
    }
}

/**
 * The first sighting of a camera value is the initial read. The same value already handed
 * to the parser is stale. Empty frames are neither. A repeated non-blank value is a real scan.
 * A missed frame does not clear [pending].
 */
internal data class ScanLatch(
    val pending: String? = null,
    val emitted: String? = null,
) {
    fun take(raw: String): ScanLatchResult {
        val text = raw.trimAsciiWhitespace()
        if (text.isEmpty() || text == emitted) return ScanLatchResult(latch = this, value = null)
        if (text != pending) return ScanLatchResult(latch = copy(pending = text), value = null)
        return ScanLatchResult(latch = copy(pending = null, emitted = text), value = text)
    }
}

internal data class ScanLatchResult(val latch: ScanLatch, val value: String?)

/**
 * An http(s) URL that is trying to be a sign-in link.
 * The route intent, a stale unrelated URL, and the bare sign-in page are not.
 */
private fun isOpenedSignInUrl(text: String): Boolean {
    val lower = text.lowercase()
    val http = lower.startsWith("https://") || lower.startsWith("http://")
    if (!http || isBareSignInPage(text)) return false
    return "/sign-in" in lower || "pcoc.app" in lower
}

private fun isBareSignInPage(text: String): Boolean =
    text == SIGN_IN_APP_LINK ||
        text == "$SIGN_IN_ORIGIN/sign-in" ||
        text == SIGN_IN_ORIGIN ||
        text == "$SIGN_IN_ORIGIN/"
