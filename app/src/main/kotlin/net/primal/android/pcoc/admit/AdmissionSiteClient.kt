package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

fun interface AdmissionPoster {
    fun post(
        exactUrl: String,
        objectBytes: ByteArray,
        signatureHex: String,
    ): AdmissionPostResult
}

sealed class AdmissionPostResult {
    data class Decision(
        val decision: AdmissionDecision,
        val verifierRan: Boolean? = null,
    ) : AdmissionPostResult()

    data object Failed : AdmissionPostResult()

    data object NotSent : AdmissionPostResult()
}

/**
 * POSTs `{object_b64, signature}` to the exact scanned HTTPS URL.
 * Does not follow a redirect. Does not POST the admission gate. Does not send nsec.
 */
class AdmissionSiteClient(
    private val http: Call.Factory,
) : AdmissionPoster {

    override fun post(
        exactUrl: String,
        objectBytes: ByteArray,
        signatureHex: String,
    ): AdmissionPostResult =
        try {
            postReady(exactUrl = exactUrl, objectBytes = objectBytes, signatureHex = signatureHex)
        } catch (error: IOException) {
            failedPost(error)
        } catch (error: IllegalArgumentException) {
            failedPost(error)
        } catch (error: IllegalStateException) {
            failedPost(error)
        }

    private fun postReady(
        exactUrl: String,
        objectBytes: ByteArray,
        signatureHex: String,
    ): AdmissionPostResult {
        val ready = AdmissionQr.parse(exactUrl) is AdmissionQrParse.Ready
        val json = if (ready) {
            runCatchingEncode(objectBytes = objectBytes, signatureHex = signatureHex)
        } else {
            null
        }
        val request = json?.let { body ->
            Request.Builder()
                .url(exactUrl)
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
                .build()
        }
        val allowed = request != null &&
            requestTargets(requestUrl = request.url, exactUrl = exactUrl) &&
            !AdmissionQr.isAdmissionGateHost(request.url.host)
        return if (request != null && allowed) execute(request) else AdmissionPostResult.NotSent
    }

    private fun failedPost(error: Exception): AdmissionPostResult {
        Napier.w(error) { "Service admission request failed." }
        return AdmissionPostResult.Failed
    }

    private fun execute(request: Request): AdmissionPostResult =
        http.newCall(request).execute().use { response ->
            interpretAdmissionResponse(response)
        }
}

internal fun newAdmissionHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

internal fun interpretAdmissionResponse(response: Response): AdmissionPostResult {
    val redirected = response.priorResponse != null || response.isRedirect
    val body = response.body?.string().orEmpty()
    val report = if (redirected || response.code in REDIRECT_START..REDIRECT_END) {
        null
    } else {
        AdmissionPayload.parseReport(body)
    }
    return report?.let {
        AdmissionPostResult.Decision(decision = it.decision, verifierRan = it.verifierRan)
    } ?: AdmissionPostResult.Failed
}

internal fun requestTargets(requestUrl: HttpUrl, exactUrl: String): Boolean {
    val uri = runCatchingUri(exactUrl) ?: return false
    val uriPort = if (uri.port == UNSPECIFIED_PORT) HTTPS_PORT else uri.port
    val expectedPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    val sameQuery = (requestUrl.encodedQuery ?: "") == (uri.rawQuery ?: "")
    return requestUrl.scheme == HTTPS_SCHEME &&
        uri.scheme == HTTPS_SCHEME &&
        requestUrl.host.equals(uri.host, ignoreCase = true) &&
        requestUrl.port == uriPort &&
        requestUrl.encodedPath == expectedPath &&
        sameQuery
}

private fun runCatchingEncode(objectBytes: ByteArray, signatureHex: String): String? =
    try {
        AdmissionPayload.encode(objectBytes = objectBytes, signatureHex = signatureHex)
    } catch (error: IllegalArgumentException) {
        Napier.w(error) { "Service admission body was not encoded." }
        null
    }

private fun runCatchingUri(exactUrl: String): URI? =
    try {
        URI(exactUrl)
    } catch (error: java.net.URISyntaxException) {
        Napier.w(error) { "Service admission URL was not parsed." }
        null
    }

private const val HTTPS_SCHEME = "https"
private const val HTTPS_PORT = 443
private const val UNSPECIFIED_PORT = -1
private const val REDIRECT_START = 300
private const val REDIRECT_END = 399
private const val CONNECT_TIMEOUT_SECONDS = 20L
private const val READ_TIMEOUT_SECONDS = 20L
private const val CALL_TIMEOUT_SECONDS = 30L
private val JSON = "application/json".toMediaType()
