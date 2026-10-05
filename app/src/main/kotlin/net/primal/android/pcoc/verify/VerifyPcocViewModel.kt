package net.primal.android.pcoc.verify

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aakira.napier.Napier
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import net.primal.android.pcoc.hike.IndexUnreachable
import net.primal.android.pcoc.hike.LiveIndex
import net.primal.android.pcoc.hike.OsEntropy
import net.primal.android.pcoc.hike.StageLog
import net.primal.android.pcoc.hike.VerifyAny
import net.primal.android.pcoc.hike.VerifyFlight
import net.primal.android.pcoc.hike.VerifyProgress
import net.primal.android.pcoc.verify.VerifyPcocContract.Notice
import net.primal.android.pcoc.verify.VerifyPcocContract.Stage
import net.primal.android.pcoc.verify.VerifyPcocContract.UiEvent
import net.primal.android.pcoc.verify.VerifyPcocContract.UiState
import net.primal.core.utils.coroutines.DispatcherProvider

@HiltViewModel
class VerifyPcocViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val index: LiveIndex,
    private val flight: VerifyFlight,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate(reducer)

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private var run: Job? = null
    private val ticket = AtomicInteger()

    init {
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is UiEvent.FilePicked -> onFile(event.uri)
                    UiEvent.Cancel -> cancelRun()
                }
            }
        }
    }

    override fun onCleared() {
        ticket.incrementAndGet()
        flight.cancel()
        super.onCleared()
    }

    private fun cancelRun() {
        ticket.incrementAndGet()
        flight.cancel()
        run?.cancel()
        run = null
        setState { UiState() }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onFile(uri: Uri) {
        val mine = ticket.incrementAndGet()
        flight.cancel()
        run?.cancel()
        run = viewModelScope.launch {
            setState { UiState(busy = true, stage = Stage.File) }
            delay(FRAME_PAUSE_MS)
            if (mine != ticket.get()) return@launch
            try {
                when (val outcome = verifyFile(uri, mine)) {
                    Outcome.Stopped -> Unit
                    is Outcome.Verdict -> publish(mine) { outcome.toState() }
                }
            } catch (cancelled: CancellationException) {
                if (mine == ticket.get()) setState { UiState() }
                throw cancelled
            } catch (error: Exception) {
                Napier.w(error) { "Verify any did not finish." }
                publish(mine) { UiState(notice = error.toNotice()) }
            } catch (error: OutOfMemoryError) {
                Napier.w(error) { "Verify any did not finish." }
                publish(mine) { UiState(notice = Notice.DidNotFinish) }
            }
        }
    }

    private suspend fun verifyFile(uri: Uri, mine: Int): Outcome {
        val fileStarted = System.nanoTime()
        val bytes = try {
            runInterruptible(dispatchers.io()) { readObject(uri) }
        } finally {
            StageLog.debug("file read", fileStarted)
        }
        val word = if (bytes == null || mine != ticket.get()) {
            null
        } else {
            runInterruptible(dispatchers.io()) {
                flight.call {
                    VerifyAny.finish(bytes, index, OsEntropy(), progress(mine))
                }
            }
        }
        val stopped = mine != ticket.get() || !coroutineContext.isActive
        return if (stopped) Outcome.Stopped else Outcome.Verdict(word)
    }

    private fun progress(mine: Int): VerifyProgress {
        fun show(stage: Stage) {
            publish(mine) { copy(busy = true, stage = stage, notice = null, word = null) }
        }

        fun showStep(stage: Stage, done: Int, total: Int) {
            publish(mine) {
                copy(
                    busy = true,
                    stage = stage,
                    stepDone = done,
                    stepTotal = total,
                    notice = null,
                    word = null,
                )
            }
        }

        return object : VerifyProgress {
            override fun indexReads() = show(Stage.Index)

            override fun terrain() = show(Stage.Terrain)

            override fun checks() = show(Stage.Checks)

            override fun checkpoints(done: Int, total: Int) = showStep(Stage.Checkpoints, done, total)

            override fun waypoints(done: Int, total: Int) = showStep(Stage.Waypoints, done, total)

            override fun paths(done: Int, total: Int) = showStep(Stage.Paths, done, total)

            override fun leg(done: Int, total: Int) {
                publish(mine) {
                    copy(
                        busy = true,
                        stage = Stage.Legs,
                        legsDone = done,
                        legsTotal = total,
                        notice = null,
                        word = null,
                    )
                }
            }
        }
    }

    private fun publish(mine: Int, reducer: UiState.() -> UiState) {
        setState { if (mine != ticket.get()) this else reducer() }
    }

    private fun readObject(uri: Uri): ByteArray? =
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> readAll(input) }
        } catch (error: IOException) {
            Napier.w(error) { "Unable to read the PCOC file." }
            null
        } catch (error: SecurityException) {
            Napier.w(error) { "Unable to read the PCOC file." }
            null
        } catch (error: OutOfMemoryError) {
            Napier.w(error) { "The PCOC file did not fit in memory." }
            null
        }

    private fun readAll(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var read = input.read(chunk)
        while (read >= 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw IOException("cancelled")
            }
            buffer.write(chunk, 0, read)
            read = input.read(chunk)
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val READ_CHUNK_BYTES = 8 * 1024
        const val FRAME_PAUSE_MS = 50L
    }
}

private sealed interface Outcome {
    data object Stopped : Outcome

    data class Verdict(val word: VerifyAny.Word?) : Outcome
}

private fun Outcome.Verdict.toState(): UiState =
    UiState(
        busy = false,
        word = word,
        notice = if (word == null) Notice.DidNotFinish else null,
    )

private fun Exception.toNotice(): Notice =
    if (this is IndexUnreachable) Notice.IndexUnreachable else Notice.DidNotFinish
