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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.primal.android.R
import net.primal.android.pcoc.bridge.INDEX_URL
import net.primal.android.pcoc.bridge.VerifyEngine
import net.primal.android.pcoc.bridge.VerifyNativeOutcome
import net.primal.android.pcoc.bridge.indexStatus
import net.primal.android.pcoc.verify.VerifyPcocContract.MeasureRow
import net.primal.android.pcoc.verify.VerifyPcocContract.Notice
import net.primal.android.pcoc.verify.VerifyPcocContract.UiEvent
import net.primal.android.pcoc.verify.VerifyPcocContract.UiState
import net.primal.core.utils.coroutines.DispatcherProvider

@HiltViewModel
class VerifyPcocViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VerifyEngine,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val events = kotlinx.coroutines.flow.MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    private var run: Job? = null
    private val ticket = AtomicInteger()
    private val gate = Mutex()

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
        ticket.incrementAndGet()
        _state.update { UiState(busy = true, stopping = true) }
    }

    private fun onFile(uri: Uri) {
        val mine = ticket.incrementAndGet()
        run?.cancel()
        run = viewModelScope.launch {
            _state.value = UiState(busy = true)
            try {
                val outcome = verifyFile(uri, mine)
                if (mine == ticket.get()) {
                    _state.value = outcome.toUi()
                } else {
                    _state.update { current -> if (current.stopping) UiState() else current }
                }
            } catch (cancelled: CancellationException) {
                _state.update { current -> if (current.stopping && mine != ticket.get()) UiState() else current }
                throw cancelled
            }
        }
    }

    private suspend fun verifyFile(uri: Uri, mine: Int): Outcome = withContext(dispatchers.io()) {
        gate.withLock {
            if (mine != ticket.get()) return@withLock Outcome.Stopped
            val bytes = readObject(uri) ?: return@withLock Outcome.Unread
            if (mine != ticket.get()) return@withLock Outcome.Stopped
            try {
                when (val native = engine.verify(bytes, INDEX_URL, VERIFY_THREADS)) {
                    is VerifyNativeOutcome.Record -> Outcome.Recorded(rowsOf(native))
                    VerifyNativeOutcome.Unreadable -> Outcome.Unreadable
                    is VerifyNativeOutcome.Engine -> Outcome.Unfinished
                }
            } catch (error: Exception) {
                Napier.w(error) { "Verify did not finish." }
                Outcome.Unfinished
            }
        }
    }

    private fun rowsOf(record: VerifyNativeOutcome.Record): List<MeasureRow> {
        val presented = presentRecord(record.json) ?: return emptyList()
        val lead = listOf(
            MeasureRow(R.string.pcoc_verify_row_bytes, thousands(record.bytes)),
            MeasureRow(R.string.pcoc_verify_row_sha256, record.sha256),
        )
        val body = when (presented) {
            is Presented.Earlier -> listOf(
                MeasureRow(R.string.pcoc_verify_row_version, "earlier version"),
                MeasureRow(R.string.pcoc_verify_row_kind, presented.kind.ifEmpty { "absent" }),
            )
            is Presented.Record -> listOf(
                MeasureRow(R.string.pcoc_verify_row_profile, presented.profile),
                MeasureRow(R.string.pcoc_verify_row_shape, presented.shape),
                MeasureRow(R.string.pcoc_verify_row_seed, presented.seed),
                MeasureRow(R.string.pcoc_verify_row_headers, presented.headers),
                MeasureRow(R.string.pcoc_verify_row_checkpoints, presented.checkpoints),
                MeasureRow(R.string.pcoc_verify_row_waypoints, presented.waypoints),
                MeasureRow(R.string.pcoc_verify_row_paths, presented.paths),
                MeasureRow(R.string.pcoc_verify_row_six_leg, presented.sixLeg),
                MeasureRow(R.string.pcoc_verify_row_span, presented.span),
                MeasureRow(
                    R.string.pcoc_verify_row_index,
                    indexStatus(record.indexUrl.ifEmpty { INDEX_URL }, record.issued, record.answered, record.totalMs),
                ),
            )
        }
        return lead + body
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
        const val VERIFY_THREADS = 6
    }
}

private sealed interface Outcome {
    data object Stopped : Outcome

    data object Unread : Outcome

    data object Unreadable : Outcome

    data object Unfinished : Outcome

    data class Recorded(val rows: List<MeasureRow>) : Outcome
}

private fun Outcome.toUi(): UiState =
    when (this) {
        Outcome.Stopped -> UiState()
        Outcome.Unread -> UiState(notice = Notice.CouldNotRead)
        Outcome.Unreadable -> UiState(notice = Notice.Unreadable)
        Outcome.Unfinished -> UiState(notice = Notice.DidNotFinish)
        is Outcome.Recorded -> if (rows.isEmpty()) {
            UiState(notice = Notice.Unreadable)
        } else {
            UiState(recorded = true, rows = rows)
        }
    }
