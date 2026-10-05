package net.primal.android.pcoc.enroll

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
import net.primal.android.pcoc.enroll.EnrollPcocContract.Notice
import net.primal.android.pcoc.enroll.EnrollPcocContract.UiEvent
import net.primal.android.pcoc.enroll.EnrollPcocContract.UiState
import net.primal.android.pcoc.hike.HikeYieldedPublicKeyReader
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider

@HiltViewModel
class EnrollPcocViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeAccountStore: ActiveAccountStore,
    reader: HikeYieldedPublicKeyReader,
    private val store: EnrolledPcocStore,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val enroll = EnrollPcoc(reader = reader, store = store)

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate(reducer)

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeEvents()
        observeAccount()
    }

    private fun observeAccount() {
        viewModelScope.launch {
            activeAccountStore.activeUserId.collect { userId ->
                val stored = withContext(dispatchers.io()) { store.contains(userId) }
                setState { copy(stored = stored) }
            }
        }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is UiEvent.FilePicked -> onFile(event.uri)
                    UiEvent.RequestClear -> setState { copy(confirmClear = true) }
                    UiEvent.DismissClear -> setState { copy(confirmClear = false) }
                    UiEvent.ConfirmClear -> confirmClear()
                }
            }
        }
    }

    private fun onFile(uri: Uri) {
        viewModelScope.launch {
            setState { copy(busy = true, notice = null) }
            val userId = activeAccountStore.activeUserId()
            val bytes = withContext(dispatchers.io()) { readObject(uri) }
            val notice = if (bytes == null) {
                Notice.Unreadable
            } else {
                withContext(dispatchers.io()) { enrollObject(userId = userId, bytes = bytes) }
            }
            val stored = withContext(dispatchers.io()) { store.contains(userId) }
            setState { copy(busy = false, stored = stored, notice = notice) }
        }
    }

    private fun enrollObject(userId: String, bytes: ByteArray): Notice =
        try {
            enroll.enroll(objectBytes = bytes, accountHex = userId).toNotice()
        } catch (error: IOException) {
            Napier.w(error) { "Could not store the PCOC object." }
            Notice.NotStored
        }

    private fun confirmClear() {
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            withContext(dispatchers.io()) { enroll.clear(userId) }
            val stored = withContext(dispatchers.io()) { store.contains(userId) }
            setState { copy(confirmClear = false, stored = stored, notice = Notice.Cleared) }
        }
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
        EnrollOutcome.Withheld -> Notice.Withheld
        EnrollOutcome.NoPublicKey -> Notice.NoPublicKey
        EnrollOutcome.Undecodable -> Notice.Undecodable
        EnrollOutcome.ReaderUnavailable -> Notice.ReaderUnavailable
        EnrollOutcome.NoAccountKey -> Notice.NoAccountKey
    }
