package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

fun interface AdmissionPoster {
    fun post(
        link: SignInLink,
        objectBytes: ByteArray,
        signatureHex: String,
    ): AdmissionPostResult
}

enum class DeliveryFailure {
    NoConnection,
    TimedOut,
    Redirected,
    UnexpectedResponse,
    NotSent,
}

/** The three client classes of SPEC §15.1. The website owns every other outcome. */
sealed class AdmissionPostResult {
    data object Submitted : AdmissionPostResult()

    data object BadSignature : AdmissionPostResult()

    data object Malformed : AdmissionPostResult()

    data class DeliveryFailed(val failure: DeliveryFailure) : AdmissionPostResult()
}

/**
 * POSTs the §15.1 body once to [SIGN_IN_DOOR]. The scanned link is never the POST target.
 * Does not follow a redirect, does not retry, and does not poll.
 */
class AdmissionDoorClient(
    private val http: Call.Factory,
) : AdmissionPoster {

    override fun post(
        link: SignInLink,
        objectBytes: ByteArray,
        signatureHex: String,
    ): AdmissionPostResult {
        val encoded = try {
            AdmissionPayload.encode(link = link, objectBytes = objectBytes, signatureHex = signatureHex)
        } catch (error: IllegalArgumentException) {
            Napier.w(error) { "Sign-in body was not encoded." }
            return AdmissionPostResult.DeliveryFailed(DeliveryFailure.NotSent)
        }
        AdmissionTrace.post(link = link, objectBytes = objectBytes.size, signatureHex = signatureHex)
        val request = Request.Builder()
            .url(SIGN_IN_DOOR)
            .post(encoded.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use(::interpretAdmissionResponse)
        } catch (error: InterruptedIOException) {
            failed(error, DeliveryFailure.TimedOut)
        } catch (error: IOException) {
            failed(error, DeliveryFailure.NoConnection)
        } catch (error: IllegalArgumentException) {
            failed(error, DeliveryFailure.NoConnection)
        } catch (error: IllegalStateException) {
            failed(error, DeliveryFailure.NoConnection)
        }
    }

    private fun failed(error: Exception, failure: DeliveryFailure): AdmissionPostResult {
        Napier.w(error) { "Sign-in POST was not delivered." }
        return AdmissionPostResult.DeliveryFailed(failure)
    }
}

/**
 * HTTP 400 `bad_signature` and HTTP 400 `malformed` are the two refusals the client shows.
 * A redirect and any other HTTP 400 are not a delivery. Every other completed response is delivery.
 */
internal fun interpretAdmissionResponse(response: Response): AdmissionPostResult {
    val body = response.body?.string().orEmpty()
    AdmissionTrace.http(status = response.code)
    val redirected = response.priorResponse != null || response.code in REDIRECT_START..REDIRECT_END
    return when {
        redirected -> AdmissionPostResult.DeliveryFailed(DeliveryFailure.Redirected)
        response.code == HTTP_BAD_REQUEST -> when (AdmissionPayload.errorCode(body)) {
            BAD_SIGNATURE -> AdmissionPostResult.BadSignature
            MALFORMED -> AdmissionPostResult.Malformed
            else -> AdmissionPostResult.DeliveryFailed(DeliveryFailure.UnexpectedResponse)
        }
        else -> AdmissionPostResult.Submitted
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

const val SIGN_IN_DOOR = "https://admission.pcoc.app/v1/sign-in"

private const val BAD_SIGNATURE = "bad_signature"
private const val MALFORMED = "malformed"
private const val HTTP_BAD_REQUEST = 400
private const val REDIRECT_START = 300
private const val REDIRECT_END = 399
private const val CONNECT_TIMEOUT_SECONDS = 20L
private const val READ_TIMEOUT_SECONDS = 20L
private const val CALL_TIMEOUT_SECONDS = 30L
private val JSON = "application/json".toMediaType()
