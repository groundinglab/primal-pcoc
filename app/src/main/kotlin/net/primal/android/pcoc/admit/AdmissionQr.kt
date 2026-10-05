package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import java.net.URI
import net.primal.android.pcoc.hike.ACCOUNT_KEY_SIZE
import net.primal.core.utils.runCatching

/**
 * One HTTPS service URL. Query `h` is 64 hex in Bitcoin display order.
 * A bare hash is not this QR. The admission gate host is never a phone POST target.
 */
sealed class AdmissionQrParse {
    data class Ready(
        val exactUrl: String,
        val hashHex: String,
        val hashBytes: ByteArray,
    ) : AdmissionQrParse()

    data object NotHttps : AdmissionQrParse()

    data object BareHash : AdmissionQrParse()

    data object MissingOrBadHash : AdmissionQrParse()

    data object GateUrl : AdmissionQrParse()

    data object NotAServiceUrl : AdmissionQrParse()
}

object AdmissionQr {
    fun parse(raw: String): AdmissionQrParse {
        val exact = raw.trim()
        return when {
            exact.isEmpty() -> AdmissionQrParse.NotAServiceUrl
            BARE_HASH.matches(exact) -> AdmissionQrParse.BareHash
            !exact.startsWith(HTTPS_PREFIX) -> AdmissionQrParse.NotHttps
            else -> parseHttps(exact)
        }
    }

    private fun parseHttps(exact: String): AdmissionQrParse {
        val uri = runCatching { URI(exact) }.getOrNull()
        val host = uri?.host
        return when {
            uri == null || uri.scheme != HTTPS_SCHEME || host.isNullOrBlank() -> AdmissionQrParse.NotAServiceUrl
            uri.rawFragment != null -> AdmissionQrParse.NotAServiceUrl
            isAdmissionGateHost(host) -> AdmissionQrParse.GateUrl
            else -> readyOrBadHash(exact = exact, rawQuery = uri.rawQuery)
        }
    }

    private fun readyOrBadHash(exact: String, rawQuery: String?): AdmissionQrParse {
        val values = queryValues(rawQuery = rawQuery, name = HASH_QUERY)
        val hash = values.singleOrNull()
        val bytes = hash?.let { candidate ->
            if (HASH_HEX.matches(candidate)) runCatching { Hex.decode(candidate) }.getOrNull() else null
        }
        return if (bytes != null && bytes.size == ACCOUNT_KEY_SIZE && hash != null) {
            AdmissionQrParse.Ready(
                exactUrl = exact,
                hashHex = hash.lowercase(),
                hashBytes = bytes,
            )
        } else {
            AdmissionQrParse.MissingOrBadHash
        }
    }

    internal fun isAdmissionGateHost(host: String?): Boolean {
        val normalized = host?.trim()?.trimEnd('.')?.lowercase()
        return normalized == ADMISSION_GATE_HOST
    }
}

internal fun queryValues(rawQuery: String?, name: String): List<String> {
    if (rawQuery.isNullOrEmpty()) return emptyList()
    return rawQuery.split('&').mapNotNull { pair ->
        val parts = pair.split('=', limit = 2)
        val key = parts.getOrNull(0)?.let(::decodeQuery)
        val value = parts.getOrNull(1)?.let(::decodeQuery)
        if (key == name) value else null
    }
}

private fun decodeQuery(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull() ?: value

internal const val ADMISSION_GATE_HOST = "admission.pcoc.app"

private const val HTTPS_PREFIX = "https://"
private const val HTTPS_SCHEME = "https"
private const val HASH_QUERY = "h"
private val BARE_HASH = Regex("""^(?:0x)?[0-9a-fA-F]{64}$""")
private val HASH_HEX = Regex("""^[0-9a-fA-F]{64}$""")
