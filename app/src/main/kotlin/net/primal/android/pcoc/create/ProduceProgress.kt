package net.primal.android.pcoc.create

import java.util.Locale
import net.primal.android.pcoc.verify.thousands

internal const val LEG_RATE_TICKS = 20
private const val MILLISECONDS_PER_SECOND = 1000.0

internal fun noteLegTick(ticks: List<Long>, nowMs: Long): List<Long> =
    if (nowMs <= 0L) ticks else (ticks + nowMs).takeLast(LEG_RATE_TICKS)

/** Legs per second across the ticks kept for the current rate. Null until two ticks differ. */
internal fun legsPerSecond(ticks: List<Long>): Double? {
    if (ticks.size < 2) return null
    val span = ticks.last() - ticks.first()
    if (span <= 0L) return null
    return (ticks.size - 1).toDouble() * MILLISECONDS_PER_SECOND / span.toDouble()
}

internal fun formatLegRate(perSecond: Double): String =
    String.format(Locale.US, "%.2f legs/s", perSecond)

/**
 * A paths report is the end of fetching. `path_skip N` was not stored.
 * `not fetched by design N` is W_17. Any other posted waypoint was fetched: anchored.
 * Lines that name neither leave the list as it is.
 */
internal fun applyPathLines(rows: List<WaypointRow>, lines: List<String>): List<WaypointRow> {
    val skipped = lines.mapNotNull { prefixedIndex(it, "path_skip ") }.toSet()
    val notFetched = lines.mapNotNull { prefixedIndex(it, "not fetched by design ") }.toSet()
    if (skipped.isEmpty() && notFetched.isEmpty()) return rows
    return rows.map { row ->
        when {
            row.index in skipped -> row.copy(status = WaypointStatus.Skipped)
            row.index in notFetched -> row.copy(status = WaypointStatus.NotFetched)
            row.status == WaypointStatus.Posted -> row.copy(status = WaypointStatus.Anchored)
            else -> row
        }
    }
}

internal fun waypointLabel(row: WaypointRow): String {
    val status = when (row.status) {
        WaypointStatus.Posted -> "posted"
        WaypointStatus.Anchored -> "anchored"
        WaypointStatus.Skipped -> "skipped"
        WaypointStatus.NotFetched -> "not fetched by design"
    }
    return "W_${row.index} · $status"
}

internal fun closedStageLabel(stage: ClosedStage): String =
    "Stage ${stage.stage} · ${thousands(stage.blockHeight)}"

/** Shown once the hike has returned. Null until the object digest and size are known. */
internal fun affirmation(state: CreateUiState): String? {
    if (state.phase != ProducePhase.Written) return null
    val sha = state.objectSha256 ?: return null
    val bytes = state.objectBytes ?: return null
    val anchored = state.waypoints.count { it.status == WaypointStatus.Anchored }
    val skipped = state.waypoints.count { it.status == WaypointStatus.Skipped }
    val notFetched = state.waypoints.count { it.status == WaypointStatus.NotFetched }
    val anchoredWords = if (anchored == 1) "waypoint" else "waypoints"
    val extra = if (notFetched == 0) "" else " $notFetched not fetched by design."
    return "${state.stagesClosed} of $HIKE_STAGES stages completed. " +
        "${thousands(state.legsTotal)} legs. " +
        "$anchored $anchoredWords anchored, $skipped skipped.$extra " +
        "SHA-256 $sha. ${thousands(bytes)} bytes."
}

private fun prefixedIndex(line: String, prefix: String): Int? {
    if (!line.startsWith(prefix)) return null
    return line.removePrefix(prefix).trim().substringBefore(' ').toIntOrNull()
}
