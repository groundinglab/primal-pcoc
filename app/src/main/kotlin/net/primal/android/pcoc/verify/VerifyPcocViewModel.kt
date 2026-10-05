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
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.pcoc.hike.LiveIndex
import net.primal.android.pcoc.hike.VerifyAny
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

    init {
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is UiEvent.FilePicked -> onFile(event.uri)
                }
            }
        }
    }

    private fun onFile(uri: Uri) {
        viewModelScope.launch {
            setState { copy(busy = true, word = null) }
            val bytes = withContext(dispatchers.io()) { readObject(uri) }
            val word = if (bytes == null) {
                null
            } else {
                withContext(dispatchers.io()) { VerifyAny.finish(bytes, index) }
            }
            setState { copy(busy = false, word = word) }
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
    }
}
