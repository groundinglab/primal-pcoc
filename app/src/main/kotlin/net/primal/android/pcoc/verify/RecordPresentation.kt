package net.primal.android.pcoc.verify

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal sealed class Presented {
    data class Earlier(val kind: String) : Presented()

    data class Record(
        val profile: String,
        val shape: String,
        val seed: String,
        val headers: String,
        val checkpoints: String,
        val waypoints: String,
        val paths: String,
        val sixLeg: String,
        val span: String,
    ) : Presented()
}

/** Section 14 JSON as the fields the screen lists. Null when it is not that object. */
internal fun presentRecord(json: String): Presented? {
    val obj = runCatching { Json.parseToJsonElement(json.trim()).jsonObject }.getOrNull() ?: return null
    if (obj.string("kind") == "unknown kind") {
        return Presented.Earlier(kind = obj.string("kind_text").orEmpty())
    }
    if (obj.string("version") == "earlier version") {
        return Presented.Earlier(kind = obj.string("kind").orEmpty())
    }
    val profile = obj.string("profile") ?: return null
    val shapeItems = obj.strings("shape_item")
    val shape = obj.string("shape") ?: "absent"
    return Presented.Record(
        profile = profile,
        shape = if (shapeItems.isEmpty()) shape else "$shape · ${shapeItems.joinToString(" · ")}",
        seed = obj.string("seed_value") ?: "absent",
        headers = tally(obj.array("headers").map { it.string("index_comparison") ?: "absent" }),
        checkpoints = tally(checkpointResults(obj)),
        waypoints = tally(obj.array("waypoints").map { it.string("result") ?: "absent" }),
        paths = pathsSentence(obj),
        sixLeg = sixLegSentence(obj),
        span = spanText(obj),
    )
}

internal fun thousands(value: Long): String = String.format(Locale.US, "%,d", value)

private fun checkpointResults(obj: JsonObject): List<String> =
    obj.array("checkpoints").flatMap { stage ->
        stage.array("legs").map { leg -> leg.string("result") ?: "absent" }
    }

private fun pathsSentence(obj: JsonObject): String {
    val states = mutableListOf<String>()
    var w17 = "absent"
    obj.array("paths").forEach { row ->
        val g = row.long("g")
        if (g == 17L) {
            w17 = row.string("w17_path") ?: "absent"
        } else {
            states += row.string("path_state") ?: "absent"
        }
    }
    val body = tally(states)
    return "$body · W_17 $w17"
}

private fun sixLegSentence(obj: JsonObject): String {
    val six = obj["six_leg"] ?: return "absent"
    val primitive = six as? JsonPrimitive
    if (primitive != null) return primitive.contentOrNull ?: "absent"
    val results = (six as? JsonObject)?.strings("results")
    return if (results == null) "absent" else tally(results)
}

private fun spanText(obj: JsonObject): String {
    val span = obj["analysis"]?.let { it as? JsonObject }?.get("whole_hike_span")
    val number = (span as? JsonPrimitive)?.longOrNull
    return when {
        number != null -> "${thousands(number)} s"
        span is JsonPrimitive -> span.contentOrNull ?: "absent"
        else -> "absent"
    }
}

private fun tally(tokens: List<String>): String {
    if (tokens.isEmpty()) return "none recorded"
    val counts = linkedMapOf<String, Int>()
    tokens.forEach { token -> counts[token] = (counts[token] ?: 0) + 1 }
    if (counts.size == 1) return "every one is ${counts.keys.first()}"
    return counts.entries.joinToString(" · ") { (token, count) -> "${thousands(count.toLong())} $token" }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.array(name: String): List<JsonObject> =
    (this[name] as? JsonArray)?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

private fun JsonObject.strings(name: String): List<String> =
    (this[name] as? JsonArray)?.map { element ->
        val primitive = element as? JsonPrimitive
        if (primitive != null && primitive.isString) primitive.content else "absent"
    }.orEmpty()
