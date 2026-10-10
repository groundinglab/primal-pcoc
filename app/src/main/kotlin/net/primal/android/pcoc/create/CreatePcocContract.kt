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
    val tipHeight: Long? = null,
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
