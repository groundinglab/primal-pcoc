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

private data class OutboundPost(
    val ready: AdmissionQrParse.Ready,
    val encoded: String,
    val request: Request,
)

/**
 * What the phone may say after one POST.
 * Delivered is not welcome or refused. BadSignature is not a policy refusal.
 * There is no provisional, checking, or revoke state.
 */
sealed class AdmissionPostResult {
    data object Delivered : AdmissionPostResult()

    data object BadSignature : AdmissionPostResult()

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
        val outbound = outboundPost(
            exactUrl = exactUrl,
            objectBytes = objectBytes,
            signatureHex = signatureHex,
        )
        return if (outbound == null) {
            val ready = AdmissionQr.parse(exactUrl) as? AdmissionQrParse.Ready
            AdmissionTrace.refused(
                reason = "not-sent",
                pubkeyMatch = null,
                hBytes = ready?.hashBytes?.size,
                target = admissionTarget(exactUrl),
            )
            AdmissionPostResult.NotSent
        } else {
            val fields = admissionBodyFields(outbound.encoded)
            AdmissionTrace.post(
                target = admissionTarget(exactUrl),
                hBytes = outbound.ready.hashBytes.size,
                hasObjectB64 = fields.hasObjectB64,
                hasSignature = fields.hasSignature,
                signatureHex = signatureHex,
            )
            execute(outbound.request)
        }
    }

    private fun outboundPost(
        exactUrl: String,
        objectBytes: ByteArray,
        signatureHex: String,
    ): OutboundPost? {
        val ready = AdmissionQr.parse(exactUrl) as? AdmissionQrParse.Ready
        val encoded = ready?.let {
            runCatchingEncode(objectBytes = objectBytes, signatureHex = signatureHex)
        }
        val request = encoded?.let { body ->
            Request.Builder()
                .url(exactUrl)
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
                .build()
        }
        val addressed = request?.let { canSend(request = it, exactUrl = exactUrl) } == true
        return if (!addressed) {
            null
        } else if (ready != null && encoded != null && request != null) {
            OutboundPost(ready = ready, encoded = encoded, request = request)
        } else {
            null
        }
    }

    private fun canSend(request: Request, exactUrl: String): Boolean =
        requestTargets(requestUrl = request.url, exactUrl = exactUrl) &&
            !AdmissionQr.isAdmissionGateHost(request.url.host)

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

/**
 * Fire-and-forget. A 2xx body is delivery, including accept, deny, provisional, or empty.
 * `bad_signature` is the only named error. Anything else that is not a clean 2xx
 * is a delivery failure. Redirects are not followed and are not a verdict.
 */
internal fun interpretAdmissionResponse(response: Response): AdmissionPostResult {
    val redirected = response.priorResponse != null || response.isRedirect
    val body = response.body?.string().orEmpty()
    AdmissionTrace.http(status = response.code, body = body)
    val sawRedirect = redirected || response.code in REDIRECT_START..REDIRECT_END
    val badSignature = !sawRedirect && AdmissionPayload.isBadSignature(body)
    val delivered = !sawRedirect && !badSignature && response.code in SUCCESS_START..SUCCESS_END
    return when {
        badSignature -> AdmissionPostResult.BadSignature
        delivered -> AdmissionPostResult.Delivered
        else -> AdmissionPostResult.Failed
    }
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
private const val SUCCESS_START = 200
private const val SUCCESS_END = 299
private const val REDIRECT_START = 300
private const val REDIRECT_END = 399
private const val CONNECT_TIMEOUT_SECONDS = 20L
private const val READ_TIMEOUT_SECONDS = 20L
private const val CALL_TIMEOUT_SECONDS = 30L
private val JSON = "application/json".toMediaType()
