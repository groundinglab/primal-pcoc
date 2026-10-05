package net.primal.android.pcoc.hike

import io.github.aakira.napier.Napier
import java.util.logging.Level
import java.util.logging.Logger

/** Debug timing for one verify stage. The line is the stage name and the elapsed milliseconds. */
object StageLog {
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private val jul = Logger.getLogger("pcoc.hike")

    @JvmStatic
    fun debug(stage: String, startedNanos: Long) {
        write("$stage ${elapsedMs(startedNanos)} ms")
    }

    /** Same clock as [debug], with how many records that stage walked. */
    @JvmStatic
    fun debugRecords(stage: String, records: Int, startedNanos: Long) {
        write("$stage $records records ${elapsedMs(startedNanos)} ms")
    }

    private fun elapsedMs(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / NANOS_PER_MILLISECOND

    private fun write(message: String) {
        jul.log(Level.FINE, message)
        try {
            Napier.d(tag = "pcoc-hike") { message }
        } catch (ignored: RuntimeException) {
            // A logger must not fail the verify.
        }
    }
}
