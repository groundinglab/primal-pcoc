package net.primal.android.pcoc.bridge

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

interface VerifyEngine {
    fun verify(bytes: ByteArray, indexUrl: String, threads: Int): VerifyNativeOutcome
}

sealed class VerifyNativeOutcome {
    data class Record(
        val json: String,
        val bytes: Long,
        val sha256: String,
        val issued: Int,
        val answered: Int,
        val totalMs: Long,
        val indexUrl: String,
    ) : VerifyNativeOutcome()

    data object Unreadable : VerifyNativeOutcome()

    data class Engine(val message: String) : VerifyNativeOutcome()
}

internal class NativeVerifyEngine @Inject constructor() : VerifyEngine {
    override fun verify(bytes: ByteArray, indexUrl: String, threads: Int): VerifyNativeOutcome =
        parseVerifyOutcome(PcocNative.verify(bytes, indexUrl, threads))
}

internal fun parseVerifyOutcome(text: String): VerifyNativeOutcome {
    val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return VerifyNativeOutcome.Engine("the hike engine returned nothing this app can read")
    return when (obj.string("outcome")) {
        "unreadable" -> VerifyNativeOutcome.Unreadable
        "record" -> recordOutcome(obj)
        else -> VerifyNativeOutcome.Engine(obj.string("message") ?: "the hike engine stopped unexpectedly")
    }
}

private fun recordOutcome(obj: JsonObject): VerifyNativeOutcome {
    val record = obj.string("record")
    val sha = obj.string("sha256")
    val bytes = obj.long("bytes")
    return if (record == null || sha == null || bytes == null) {
        VerifyNativeOutcome.Engine("the measurement record was incomplete")
    } else {
        VerifyNativeOutcome.Record(
            json = record,
            bytes = bytes,
            sha256 = sha,
            issued = obj.int("issued") ?: 0,
            answered = obj.int("answered") ?: 0,
            totalMs = obj.long("total_ms") ?: 0L,
            indexUrl = obj.string("index_url").orEmpty(),
        )
    }
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

/** Same sentences as the desktop index line. */
internal fun indexStatus(url: String, issued: Int, answered: Int, totalMs: Long): String {
    val host = url.removePrefix("https://").removePrefix("http://")
    val reads = if (issued == 1) "1 read" else "$issued reads"
    return when {
        issued == 0 -> "Portable index not read · $host"
        answered > 0 -> "Portable index reachable · $host · ${totalMs / answered} ms · $reads"
        else -> "Portable index did not answer · $host · $reads"
    }
}
