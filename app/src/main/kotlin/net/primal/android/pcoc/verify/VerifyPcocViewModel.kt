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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import net.primal.android.pcoc.hike.IndexUnreachable
import net.primal.android.pcoc.hike.LiveIndex
import net.primal.android.pcoc.hike.OsEntropy
import net.primal.android.pcoc.hike.VerifyAny
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
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate(reducer)

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private var run: Job? = null

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

    private fun cancelRun() {
        run?.cancel()
        run = null
        setState { UiState() }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onFile(uri: Uri) {
        run?.cancel()
        val alive = AtomicBoolean(true)
        run = viewModelScope.launch {
            val mine = coroutineContext[Job]
            setState {
                UiState(busy = true, stage = Stage.Headers)
            }
            delay(FRAME_PAUSE_MS)
            val reporting = object : VerifyProgress {
                override fun headers() {
                    if (!alive.get()) return
                    setState { copy(busy = true, stage = Stage.Headers, notice = null, word = null) }
                }

                override fun terrain() {
                    if (!alive.get()) return
                    setState { copy(busy = true, stage = Stage.Terrain, notice = null, word = null) }
                }

                override fun leg(done: Int, total: Int) {
                    if (!alive.get()) return
                    setState {
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
            try {
                val bytes = runInterruptible(dispatchers.io()) { readObject(uri) }
                val word = if (bytes == null) {
                    null
                } else {
                    runInterruptible(dispatchers.io()) {
                        VerifyAny.finish(bytes, index, OsEntropy(), reporting)
                    }
                }
                if (!alive.get()) return@launch
                setState {
                    copy(
                        busy = false,
                        stage = null,
                        word = word,
                        notice = if (word == null) Notice.DidNotFinish else null,
                    )
                }
            } catch (unreachable: IndexUnreachable) {
                alive.set(false)
                Napier.w(unreachable) { "The index could not be reached." }
                setState { UiState(notice = Notice.IndexUnreachable) }
            } catch (cancelled: CancellationException) {
                alive.set(false)
                if (run == mine) {
                    setState { UiState() }
                }
                throw cancelled
            } catch (error: RuntimeException) {
                alive.set(false)
                Napier.w(error) { "Verify any did not finish." }
                setState { UiState(notice = Notice.DidNotFinish) }
            } catch (error: OutOfMemoryError) {
                alive.set(false)
                Napier.w(error) { "Verify any did not finish." }
                setState { UiState(notice = Notice.DidNotFinish) }
            }
        }
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
