package net.primal.android.pcoc.admit

import io.github.aakira.napier.Napier
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson
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

fun interface FlushPoster {
    fun postFlush(
        link: SignInLink,
        publicKey: ByteArray,
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

    data class DeliveryFailed(
        val failure: DeliveryFailure,
        val httpCode: Int? = null,
    ) : AdmissionPostResult()
}

/**
 * POSTs the §15.1 body once to [SIGN_IN_DOOR]. The scanned link is never the POST target.
 * Does not follow a redirect, does not retry, and does not poll.
 * [readResult] is one GET after a delivered POST. A miss is not a refusal.
 */
class AdmissionDoorClient(
    private val http: Call.Factory,
    private val flushDoor: String = FLUSH_DOOR,
) : AdmissionPoster, FlushPoster {

    /** Public decision log for this login. Not a sign-in result poll. */
    val decisions: GateDecisionLog = GateDecisionLog(http)

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
        AdmissionTrace.post(
            link = link,
            objectSha256 = sha256(objectBytes),
            objectBytes = objectBytes.size,
            signatureHex = signatureHex,
        )
        val request = Request.Builder()
            .url(SIGN_IN_DOOR)
            .post(encoded.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use(::interpretAdmissionResponse)
        } catch (error: InterruptedIOException) {
            failed(error, DeliveryFailure.TimedOut, what = "Sign-in POST")
        } catch (error: IOException) {
            failed(error, DeliveryFailure.NoConnection, what = "Sign-in POST")
        } catch (error: IllegalArgumentException) {
            failed(error, DeliveryFailure.NoConnection, what = "Sign-in POST")
        } catch (error: IllegalStateException) {
            failed(error, DeliveryFailure.NoConnection, what = "Sign-in POST")
        }
    }

    /**
     * POSTs the five-field flush body once to [flushDoor]. Does not follow a redirect,
     * does not retry, and does not read a result. Success is HTTP 200 `{"flushed":true}`.
     */
    override fun postFlush(
        link: SignInLink,
        publicKey: ByteArray,
        signatureHex: String,
    ): AdmissionPostResult {
        val encoded = try {
            AdmissionPayload.encodeFlush(link = link, publicKey = publicKey, signatureHex = signatureHex)
        } catch (error: IllegalArgumentException) {
            Napier.w(error) { "Reset body was not encoded." }
            return AdmissionPostResult.DeliveryFailed(DeliveryFailure.NotSent)
        }
        AdmissionTrace.flush(link = link, publicKey = publicKey, signatureHex = signatureHex)
        val request = Request.Builder()
            .url(flushDoor)
            .post(encoded.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use(::interpretFlushResponse)
        } catch (error: InterruptedIOException) {
            failed(error, DeliveryFailure.TimedOut, what = "Reset POST")
        } catch (error: IOException) {
            failed(error, DeliveryFailure.NoConnection, what = "Reset POST")
        } catch (error: IllegalArgumentException) {
            failed(error, DeliveryFailure.NoConnection, what = "Reset POST")
        } catch (error: IllegalStateException) {
            failed(error, DeliveryFailure.NoConnection, what = "Reset POST")
        }
    }

    /** One result read. No retry. Null when the response has no outcome string. */
    fun readResult(nonceHex: String): String? {
        if (!NONCE_HEX.matches(nonceHex)) return null
        val request = Request.Builder()
            .url(SIGN_IN_RESULT + nonceHex)
            .header("Accept", "application/json")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                signInResultToken(body = response.body?.string().orEmpty(), httpCode = response.code)
            }
        } catch (error: InterruptedIOException) {
            null
        } catch (error: IOException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        } catch (error: IllegalStateException) {
            null
        }
    }

    private fun failed(error: Exception, failure: DeliveryFailure, what: String): AdmissionPostResult {
        Napier.w(error) { "$what was not delivered." }
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
        redirected -> AdmissionPostResult.DeliveryFailed(
            failure = DeliveryFailure.Redirected,
            httpCode = redirectCode(response),
        )
        response.code == HTTP_BAD_REQUEST -> when (AdmissionPayload.errorCode(body)) {
            BAD_SIGNATURE -> AdmissionPostResult.BadSignature
            MALFORMED -> AdmissionPostResult.Malformed
            else -> AdmissionPostResult.DeliveryFailed(
                failure = DeliveryFailure.UnexpectedResponse,
                httpCode = response.code,
            )
        }
        else -> AdmissionPostResult.Submitted
    }
}

/**
 * Flush success is only HTTP 200 `{"flushed":true}`.
 * HTTP 400 `bad_signature` and `malformed` are the two refusals. Anything else is not a delivery.
 * There is no result read.
 */
internal fun interpretFlushResponse(response: Response): AdmissionPostResult {
    val body = response.body?.string().orEmpty()
    AdmissionTrace.http(status = response.code)
    val redirected = response.priorResponse != null || response.code in REDIRECT_START..REDIRECT_END
    return when {
        redirected -> AdmissionPostResult.DeliveryFailed(
            failure = DeliveryFailure.Redirected,
            httpCode = redirectCode(response),
        )
        response.code == HTTP_BAD_REQUEST -> when (AdmissionPayload.errorCode(body)) {
            BAD_SIGNATURE -> AdmissionPostResult.BadSignature
            MALFORMED -> AdmissionPostResult.Malformed
            else -> AdmissionPostResult.DeliveryFailed(
                failure = DeliveryFailure.UnexpectedResponse,
                httpCode = response.code,
            )
        }
        response.code == HTTP_OK && flushedTrue(body) -> AdmissionPostResult.Submitted
        else -> AdmissionPostResult.DeliveryFailed(
            failure = DeliveryFailure.UnexpectedResponse,
            httpCode = response.code,
        )
    }
}

internal fun flushedTrue(body: String): Boolean {
    val element = runCatching { CommonJson.parseToJsonElement(body) }.getOrNull() ?: return false
    val obj = runCatching { element.jsonObject }.getOrNull() ?: return false
    val primitive = obj[FLUSHED]?.let { value -> runCatching { value.jsonPrimitive }.getOrNull() } ?: return false
    return !primitive.isString && primitive.content == "true"
}

/** Outcome token from a result GET. Anything other than HTTP 200 with a string field is unsettled. */
internal fun signInResultToken(body: String, httpCode: Int): String? {
    if (httpCode != HTTP_OK) return null
    val element = runCatching { CommonJson.parseToJsonElement(body) }.getOrNull() ?: return null
    val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
    val primitive = obj[OUTCOME]?.let { value -> runCatching { value.jsonPrimitive }.getOrNull() } ?: return null
    return primitive.takeIf(JsonPrimitive::isString)?.content
}

private fun redirectCode(response: Response): Int {
    var seen = response.code
    var current: Response? = response
    while (current != null) {
        if (current.code in REDIRECT_START..REDIRECT_END) seen = current.code
        current = current.priorResponse
    }
    return seen
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

const val SIGN_IN_DOOR = "https://admission.groundinglab.io/v1/sign-in"
const val SIGN_IN_RESULT = "https://admission.groundinglab.io/v1/sign-in/result/"
const val FLUSH_DOOR = "https://admission.groundinglab.io/v1/flush"

private const val BAD_SIGNATURE = "bad_signature"
private const val MALFORMED = "malformed"
private const val OUTCOME = "outcome"
private const val FLUSHED = "flushed"
private const val HTTP_OK = 200
private const val HTTP_BAD_REQUEST = 400
private val NONCE_HEX = Regex("^[0-9a-f]{64}$")
private const val REDIRECT_START = 300
private const val REDIRECT_END = 399
private const val CONNECT_TIMEOUT_SECONDS = 20L
private const val READ_TIMEOUT_SECONDS = 20L
private const val CALL_TIMEOUT_SECONDS = 30L
private val JSON = "application/json".toMediaType()
