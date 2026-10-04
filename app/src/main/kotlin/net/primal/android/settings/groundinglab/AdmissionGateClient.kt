package net.primal.android.settings.groundinglab

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Dedicated client for `admission.pcoc.app`. Not the primal.net Retrofit client,
 * not [net.primal.android.networking.di.SocketModule], and not MempoolApiService.
 */
@Singleton
class AdmissionGateClient(
    private val http: Call.Factory,
    private val signInUrl: String,
) {

    @Inject
    constructor() : this(
        http = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build(),
        signInUrl = SIGN_IN_URL,
    )

    fun signIn(objectBytes: ByteArray, signatureHex: String): AdmissionSignInResult {
        val json = SignInPayload.encode(objectBytes = objectBytes, signatureHex = signatureHex)
        val request = Request.Builder()
            .url(signInUrl)
            .post(json.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    body.isBlank() -> AdmissionSignInResult.EmptyBody
                    else -> SignInPayload.parseDecision(body)?.let(AdmissionSignInResult::Decision)
                        ?: AdmissionSignInResult.MissingDecision
                }
            }
        } catch (_: IOException) {
            AdmissionSignInResult.TransportFailure
        }
    }

    companion object {
        const val SIGN_IN_URL = "https://admission.pcoc.app/v1/sign-in"
        private const val CONNECT_TIMEOUT_SECONDS = 20L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val CALL_TIMEOUT_SECONDS = 45L
        private val JSON = "application/json".toMediaType()
    }
}

sealed class AdmissionSignInResult {
    data class Decision(val decision: String) : AdmissionSignInResult()
    data object EmptyBody : AdmissionSignInResult()
    data object MissingDecision : AdmissionSignInResult()
    data object TransportFailure : AdmissionSignInResult()
}
