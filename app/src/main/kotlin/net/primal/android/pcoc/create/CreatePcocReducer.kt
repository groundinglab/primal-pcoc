package net.primal.android.pcoc.create

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal sealed class ProduceEvent {
    data class Started(val output: String) : ProduceEvent()

    data class Seed(val height: Long) : ProduceEvent()

    data object Terrain : ProduceEvent()

    data class Leg(val stage: Int, val stored: Long) : ProduceEvent()

    data class Tip(val height: Long) : ProduceEvent()

    data class StageClosed(val stage: Int, val legs: Long, val height: Long) : ProduceEvent()

    data class Posting(val beaconHeight: Long) : ProduceEvent()

    data object Settling : ProduceEvent()

    data class Log(val line: String) : ProduceEvent()

    data class Paths(val lines: List<String>) : ProduceEvent()

    data class Written(val path: String, val bytes: Long, val sha256: String) : ProduceEvent()

    data object Stopped : ProduceEvent()

    data class Failed(val message: String) : ProduceEvent()
}

internal fun parseProduceEvent(text: String): ProduceEvent? {
    val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return null
    return when (kind) {
        "started" -> ProduceEvent.Started(output = obj.string("output").orEmpty())
        "seed" -> obj.long("height")?.let(ProduceEvent::Seed)
        "terrain" -> ProduceEvent.Terrain
        "leg" -> {
            val stage = obj.long("stage") ?: return null
            val stored = obj.long("stored") ?: return null
            ProduceEvent.Leg(stage = stage.toInt(), stored = stored)
        }
        "tip" -> obj.long("height")?.let(ProduceEvent::Tip)
        "stage_closed" -> {
            val stage = obj.long("stage") ?: return null
            val legs = obj.long("legs") ?: return null
            val height = obj.long("height") ?: return null
            ProduceEvent.StageClosed(stage = stage.toInt(), legs = legs, height = height)
        }
        "posting" -> obj.long("beacon_height")?.let(ProduceEvent::Posting)
        "settling" -> ProduceEvent.Settling
        "log" -> ProduceEvent.Log(line = obj.string("line").orEmpty())
        "paths" -> ProduceEvent.Paths(lines = obj.strings("lines"))
        "written" -> {
            val path = obj.string("path") ?: return null
            val bytes = obj.long("bytes") ?: return null
            val sha = obj.string("sha256") ?: return null
            ProduceEvent.Written(path = path, bytes = bytes, sha256 = sha)
        }
        "stopped" -> ProduceEvent.Stopped
        "failed" -> ProduceEvent.Failed(message = obj.string("message") ?: "the hike stopped")
        else -> null
    }
}

internal fun reduce(state: CreateUiState, event: ProduceEvent, nowMs: Long = 0L): CreateUiState =
    when (event) {
        is ProduceEvent.Started -> state.copy(phase = ProducePhase.Running, outputPath = event.output)
        is ProduceEvent.Seed -> state.copy(
            phase = ProducePhase.Running,
            seedHeight = event.height,
            tipHeight = event.height,
        )
        ProduceEvent.Terrain -> state.appendLog("Terrain filled. 256 MiB held for the run.")
        is ProduceEvent.Leg -> {
            val ticks = noteLegTick(state.legTicks, nowMs)
            state.copy(
                phase = if (state.phase == ProducePhase.Settling) state.phase else ProducePhase.Running,
                stage = event.stage,
                legsInStage = event.stored,
                legsTotal = state.legsTotal + 1,
                legTicks = ticks,
                legRatePerSecond = legsPerSecond(ticks),
            )
        }
        is ProduceEvent.Tip -> state.copy(tipHeight = event.height)
        is ProduceEvent.StageClosed -> state.copy(
            stagesClosed = event.stage + 1,
            stage = event.stage + 1,
            legsInStage = 0,
            closedStages = state.closedStages + ClosedStage(stage = event.stage, blockHeight = event.height),
        ).appendLog("Stage ${event.stage} closed. ${event.legs} legs stored.")
        is ProduceEvent.Posting -> state.copy(
            // Stage 0 is not posted. The first posting event is W_1.
            waypoints = state.waypoints + WaypointRow(
                index = state.waypoints.size + 1,
                status = WaypointStatus.Posted,
            ),
        ).appendLog("Posting a waypoint. Beacon height ${event.beaconHeight}.")
        ProduceEvent.Settling -> state.copy(phase = ProducePhase.Settling)
        is ProduceEvent.Log -> state.appendLog(event.line)
        is ProduceEvent.Paths -> state.copy(
            waypoints = applyPathLines(state.waypoints, event.lines),
        ).appendLog(event.lines.joinToString("\n"))
        is ProduceEvent.Written -> state.copy(
            phase = ProducePhase.Written,
            outputPath = event.path,
            objectBytes = event.bytes,
            objectSha256 = event.sha256,
            stagesClosed = HIKE_STAGES,
        )
        ProduceEvent.Stopped -> state.copy(phase = ProducePhase.Stopped)
        is ProduceEvent.Failed -> state.copy(phase = ProducePhase.Failed, message = event.message)
    }

/** `hike-2026-10-09-0704.cbor`, UTC, matching the desktop file name. */
internal fun objectFileName(epochMs: Long): String {
    val utc = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)
    return "hike-%04d-%02d-%02d-%02d%02d.cbor".format(
        utc.year,
        utc.monthValue,
        utc.dayOfMonth,
        utc.hour,
        utc.minute,
    )
}

private const val LOG_LIMIT = 40

private fun CreateUiState.appendLog(line: String): CreateUiState {
    if (line.isBlank()) return this
    return copy(logLines = (logLines + line).takeLast(LOG_LIMIT))
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun kotlinx.serialization.json.JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

private fun kotlinx.serialization.json.JsonObject.strings(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { element ->
        runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
    }.orEmpty()
