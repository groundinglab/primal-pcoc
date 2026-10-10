package net.primal.android.pcoc.create

internal const val HIKE_STAGES = 18
internal const val HEADER_INTERVAL_MINUTES = 10

enum class ProducePhase {
    Idle,
    Running,
    Settling,
    Written,
    Stopped,
    Failed,
}

data class ClosedStage(
    val stage: Int,
    val blockHeight: Long,
)

enum class WaypointStatus {
    Posted,
    Anchored,
    Skipped,
    NotFetched,
}

data class WaypointRow(
    val index: Int,
    val status: WaypointStatus,
)

data class CreateUiState(
    val phase: ProducePhase = ProducePhase.Idle,
    val npub: String? = null,
    val accountHex: String? = null,
    val outputPath: String = "",
    val stage: Int = 0,
    val stagesClosed: Int = 0,
    val legsTotal: Long = 0,
    val legsInStage: Long = 0,
    val seedHeight: Long? = null,
    /** Bitcoin header nTime of the seed, unix seconds. Null when unread. */
    val seedTimeUnixS: Long? = null,
    /** Height of the header this stage started from. */
    val openBeaconHeight: Long? = null,
    val tipHeight: Long? = null,
    val closedStages: List<ClosedStage> = emptyList(),
    val waypoints: List<WaypointRow> = emptyList(),
    val legTicks: List<Long> = emptyList(),
    val legRatePerSecond: Double? = null,
    val logLines: List<String> = emptyList(),
    val message: String? = null,
    val objectBytes: Long? = null,
    val objectSha256: String? = null,
    val savedForAccount: Boolean = false,
)

sealed class CreateUiEvent {
    data object Start : CreateUiEvent()

    data object Stop : CreateUiEvent()

    data object Reset : CreateUiEvent()
}

const val WAITING_FOR_HEADER = "Waiting for latest Bitcoin header…"

/**
 * What Produce is doing. Waiting, then the seed block, then stage k of 18.
 * `nowEpochMs` is the app clock, used only for "mined X min ago".
 */
internal fun produceHeadline(state: CreateUiState, nowEpochMs: Long): String {
    val seed = state.seedHeight ?: return WAITING_FOR_HEADER
    if (state.legsTotal == 0L) {
        val mins = minedMinutes(state.seedTimeUnixS, nowEpochMs)
        return if (mins == null) {
            "Started on block $seed"
        } else {
            "Started on block $seed (mined $mins min ago)"
        }
    }
    val from = state.openBeaconHeight ?: seed
    val tip = state.tipHeight ?: from
    return "Stage ${state.stage} of $HIKE_STAGES · from block $from · tip $tip"
}

internal fun minedMinutes(seedTimeUnixS: Long?, nowEpochMs: Long): Long? {
    val nTime = seedTimeUnixS ?: return null
    if (nTime <= 0L) return null
    return (nowEpochMs / 1000 - nTime).coerceAtLeast(0) / 60
}

/** Headers still to come, at ten minutes each. Null once every stage has closed. */
internal fun headerEstimate(stagesClosed: Int): String? {
    val left = (HIKE_STAGES - stagesClosed).coerceAtLeast(0)
    if (left == 0) return null
    val minutes = left * HEADER_INTERVAL_MINUTES
    val hours = minutes / 60
    val rest = minutes % 60
    val clock = if (hours == 0) "$rest min" else "$hours h $rest min"
    return "About $clock left, from headers still to come."
}
