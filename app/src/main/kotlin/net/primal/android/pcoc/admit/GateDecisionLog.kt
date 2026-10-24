package net.primal.android.pcoc.admit

import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson
import okhttp3.Call
import okhttp3.Request

/**
 * Previous signings for one login, from the public gate log.
 * `GET /latest.json`, then `GET /log/{today}.jsonl`. Not a private history API.
 * Lines are kept when `public_key` is this login. The secret key and the signature are not kept.
 */
class GateDecisionLog(
    private val http: Call.Factory,
    private val latestUrl: String = GATE_LATEST,
    private val logRoot: String = GATE_LOG_ROOT,
) {
    internal fun load(publicKeyHex: String): PreviousSigningsLoad {
        val wanted = publicKeyHex.trim().lowercase()
        if (!PUBLIC_KEY.matches(wanted)) return PreviousSigningsLoad.Empty
        val latest = get(latestUrl) ?: return PreviousSigningsLoad.Unavailable
        if (latest.code == HTTP_NOT_FOUND) return PreviousSigningsLoad.Empty
        if (latest.code != HTTP_OK) return PreviousSigningsLoad.Unavailable
        val today = todayFromLatest(latest.body) ?: return PreviousSigningsLoad.Unavailable
        val log = get(logRoot + today + ".jsonl") ?: return PreviousSigningsLoad.Unavailable
        if (log.code == HTTP_NOT_FOUND) return PreviousSigningsLoad.Empty
        if (log.code != HTTP_OK) return PreviousSigningsLoad.Unavailable
        return previousSigningsFromLog(body = log.body, publicKeyHex = wanted)
    }

    private fun get(url: String): Got? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                Got(code = response.code, body = response.body?.string().orEmpty())
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

    private data class Got(val code: Int, val body: String)
}

internal data class PreviousSigning(
    val timeUnixS: Long,
    val seq: Long,
    val decision: String,
    val origin: String?,
)

internal sealed class PreviousSigningsLoad {
    data class Ready(val rows: List<PreviousSigning>) : PreviousSigningsLoad()

    data object Empty : PreviousSigningsLoad()

    data object Unavailable : PreviousSigningsLoad()
}

internal const val GATE_LATEST = "https://admission.groundinglab.io/latest.json"
internal const val GATE_LOG_ROOT = "https://admission.groundinglab.io/log/"

internal fun todayFromLatest(body: String): String? {
    val obj = jsonObject(body) ?: return null
    val today = stringField(obj, "today") ?: return null
    return today.takeIf { DAY.matches(it) }
}

/**
 * Keeps lines whose public key is [publicKeyHex] and whose decision is
 * accept, return, refuse, or provisional. Newest first.
 * A blank body is empty. A body with no JSON object is unavailable.
 */
internal fun previousSigningsFromLog(body: String, publicKeyHex: String): PreviousSigningsLoad {
    if (body.isBlank()) return PreviousSigningsLoad.Empty
    val wanted = publicKeyHex.trim().lowercase()
    if (!PUBLIC_KEY.matches(wanted)) return PreviousSigningsLoad.Empty
    var sawObject = false
    val rows = mutableListOf<PreviousSigning>()
    body.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (!line.startsWith("{")) return@forEach
        sawObject = true
        parseSigningLine(line, wanted)?.let(rows::add)
    }
    val ordered = rows.sortedWith(
        compareByDescending<PreviousSigning> { it.timeUnixS }.thenByDescending { it.seq },
    )
    return when {
        ordered.isNotEmpty() -> PreviousSigningsLoad.Ready(ordered)
        sawObject -> PreviousSigningsLoad.Empty
        else -> PreviousSigningsLoad.Unavailable
    }
}

internal fun formatPreviousSigning(row: PreviousSigning, zone: ZoneId): String {
    val time = TIME.format(Instant.ofEpochSecond(row.timeUnixS).atZone(zone))
    val origin = row.origin
    return if (origin == null) "$time · ${row.decision}" else "$time · ${row.decision} · $origin"
}

private fun parseSigningLine(line: String, wanted: String): PreviousSigning? {
    val obj = jsonObject(line) ?: return null
    val key = stringField(obj, "public_key")?.lowercase() ?: return null
    if (key != wanted) return null
    val decision = stringField(obj, "decision")?.let(::decisionWord) ?: return null
    val time = longField(obj, "time_unix_s") ?: return null
    val seq = longField(obj, "seq") ?: 0L
    return PreviousSigning(
        timeUnixS = time,
        seq = seq,
        decision = decision,
        origin = originContext(stringField(obj, "origin")),
    )
}

private fun decisionWord(token: String): String? = when (token) {
    "accept" -> "Accept"
    "return" -> "Return"
    "refuse" -> "Refuse"
    "provisional" -> "Provisional"
    else -> null
}

private fun originContext(raw: String?): String? {
    val text = raw?.trim() ?: return null
    if (text.length > MAX_ORIGIN_CHARS || text.any { it.isWhitespace() }) return null
    if (!text.startsWith("https://")) return null
    return text
}

private fun jsonObject(body: String): JsonObject? {
    if (body.isBlank()) return null
    val element = runCatching { CommonJson.parseToJsonElement(body) }.getOrNull() ?: return null
    return runCatching { element.jsonObject }.getOrNull()
}

private fun stringField(obj: JsonObject, name: String): String? {
    val primitive = obj[name]?.let { value -> runCatching { value.jsonPrimitive }.getOrNull() } ?: return null
    return primitive.takeIf(JsonPrimitive::isString)?.content
}

private fun longField(obj: JsonObject, name: String): Long? {
    val primitive = obj[name]?.let { value -> runCatching { value.jsonPrimitive }.getOrNull() } ?: return null
    if (primitive.isString) return null
    return primitive.content.toLongOrNull()
}

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private const val MAX_ORIGIN_CHARS = 80
private val PUBLIC_KEY = Regex("^[0-9a-f]{64}$")
private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US)
