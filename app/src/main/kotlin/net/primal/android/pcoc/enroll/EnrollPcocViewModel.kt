package net.primal.android.pcoc.enroll

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import net.primal.android.pcoc.PcocStoredExport
import net.primal.android.pcoc.enroll.EnrollPcocContract.Notice
import net.primal.android.pcoc.enroll.EnrollPcocContract.UiEvent
import net.primal.android.pcoc.enroll.EnrollPcocContract.UiState
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider

@HiltViewModel
class EnrollPcocViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeAccountStore: ActiveAccountStore,
    private val store: EnrolledPcocStore,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val enroll = EnrollPcoc(store = store)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate(reducer)

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    suspend fun storedObject(): PcocStoredExport? = withContext(dispatchers.io()) {
        val userId = activeAccountStore.activeUserId()
        val bytes = store.read(userId) ?: return@withContext null
        val name = store.note(userId)?.fileName?.takeIf { it.isNotBlank() } ?: "pcoc.cbor"
        PcocStoredExport(displayName = name, bytes = bytes)
    }

    init {
        observeEvents()
        observeAccount()
    }

    private fun observeAccount() {
        viewModelScope.launch {
            activeAccountStore.activeUserId.collect { userId ->
                val stored = withContext(dispatchers.io()) { store.contains(userId) }
                val note = if (stored) withContext(dispatchers.io()) { store.note(userId) } else null
                setState {
                    if (busy) {
                        this
                    } else {
                        copy(
                            stored = stored,
                            fileName = note?.fileName,
                            savedAtEpochMs = note?.savedAtEpochMs,
                        )
                    }
                }
            }
        }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is UiEvent.FilePicked -> onFile(event.uri)
                    UiEvent.RequestClear -> if (!_state.value.busy) setState { copy(confirmClear = true) }
                    UiEvent.DismissClear -> setState { copy(confirmClear = false) }
                    UiEvent.ConfirmClear -> confirmClear()
                }
            }
        }
    }

    private fun onFile(uri: Uri) {
        if (_state.value.busy) return
        setState { copy(busy = true, notice = null) }
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            val bytes = withContext(dispatchers.io()) { readObject(uri) }
            val fileName = withContext(dispatchers.io()) { displayName(uri) }
            val notice = if (bytes == null) {
                Notice.Unreadable
            } else {
                withContext(dispatchers.io()) {
                    enrollObject(userId = userId, bytes = bytes, fileName = fileName)
                }
            }
            val stored = withContext(dispatchers.io()) { store.contains(userId) }
            val note = if (stored) withContext(dispatchers.io()) { store.note(userId) } else null
            setState {
                copy(
                    busy = false,
                    stored = stored,
                    fileName = note?.fileName,
                    savedAtEpochMs = note?.savedAtEpochMs,
                    notice = notice,
                )
            }
        }
    }

    private fun enrollObject(userId: String, bytes: ByteArray, fileName: String): Notice =
        try {
            enroll.enroll(
                objectBytes = bytes,
                accountHex = userId,
                fileName = fileName,
                savedAtEpochMs = System.currentTimeMillis(),
            ).toNotice()
        } catch (error: IOException) {
            Napier.w(error) { "Could not store the PCOC object." }
            Notice.NotStored
        }

    private fun confirmClear() {
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            withContext(dispatchers.io()) { enroll.clear(userId) }
            val stored = withContext(dispatchers.io()) { store.contains(userId) }
            val note = if (stored) withContext(dispatchers.io()) { store.note(userId) } else null
            setState {
                copy(
                    confirmClear = false,
                    stored = stored,
                    fileName = note?.fileName,
                    savedAtEpochMs = note?.savedAtEpochMs,
                    notice = Notice.Cleared,
                )
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val raw = try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        } catch (error: SecurityException) {
            Napier.w(error) { "Unable to read the PCOC file name." }
            null
        } catch (error: IllegalArgumentException) {
            Napier.w(error) { "Unable to read the PCOC file name." }
            null
        }
        return displayFileName(raw)
    }

    private fun readObject(uri: Uri): ByteArray? =
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> readCapped(input) }
        } catch (error: IOException) {
            Napier.w(error) { "Unable to read the PCOC file." }
            null
        } catch (error: SecurityException) {
            Napier.w(error) { "Unable to read the PCOC file." }
            null
        }

    private fun readCapped(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        var read = input.read(chunk)
        while (read >= 0) {
            total += read
            if (total > MAX_OBJECT_BYTES) return null
            buffer.write(chunk, 0, read)
            read = input.read(chunk)
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val READ_CHUNK_BYTES = 8 * 1024
        const val MAX_OBJECT_BYTES = 32 * 1024 * 1024
    }
}

private fun EnrollOutcome.toNotice(): Notice =
    when (this) {
        EnrollOutcome.Stored -> Notice.Stored
        EnrollOutcome.NotStored -> Notice.NotStored
    }
