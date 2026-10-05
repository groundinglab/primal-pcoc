package net.primal.android.scanner.domain

/**
 * Website sign-in QR: the tip `header.hash`, 64 hex characters in Bitcoin display order.
 * Height may sit in the same scan. Height is never the signed message and never a request field.
 */
object TipHashQr {

    private val exactHash = Regex("""^(?:0x)?([0-9a-f]{64})$""", RegexOption.IGNORE_CASE)
    private val hashToken = Regex("""(?<![0-9A-Fa-f])([0-9A-Fa-f]{64})(?![0-9A-Fa-f])""")

    /**
     * @return the 64-hex tip hash in display order, lowercased, or null when the scan is not that hash.
     * A height by itself does not match. When height and one hash are both present, only the hash is returned.
     * Two hash tokens are ambiguous, even when they are the same hash, and do not match.
     */
    fun parse(raw: String): String? {
        val trimmed = raw.trim()
        exactHash.matchEntire(trimmed)?.let { return it.groupValues[1].lowercase() }

        val hashes = hashToken.findAll(trimmed).map { it.groupValues[1].lowercase() }.toList()
        return hashes.singleOrNull()
    }
}
