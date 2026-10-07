package net.primal.android.settings.groundinglab

import android.content.ContentResolver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.scanner.domain.TipHashQr
import net.primal.android.settings.groundinglab.GroundinglabContract.SignInError
import net.primal.android.settings.groundinglab.GroundinglabContract.Stage
import net.primal.android.settings.groundinglab.GroundinglabContract.UiEvent
import net.primal.android.settings.groundinglab.GroundinglabContract.UiState
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching

/**
 * QUARANTINED. Settings does not open this screen.
 * Sign-in does not POST [AdmissionGateClient]. Do not put this ViewModel back on a route.
 * Request service admission posts to the scanned website URL.
 */
@HiltViewModel
class GroundinglabViewModel @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    init {
        observeEvents()
    }

    private fun observeEvents() =
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is UiEvent.ImportFile -> importFile(event)
                    UiEvent.OpenScanner -> setState { copy(stage = Stage.Scan, error = null) }
                    UiEvent.CloseScanner -> setState { copy(stage = Stage.Ready) }
                    is UiEvent.QrTextDetected -> onQrText(event.value)
                    UiEvent.SignIn -> signIn()
                }
            }
        }

    private fun importFile(event: UiEvent.ImportFile) =
        viewModelScope.launch {
            val bytes = withContext(dispatcherProvider.io()) {
                runCatching {
                    contentResolver.openInputStream(event.uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            when {
                bytes == null -> setState { copy(error = SignInError.UnreadableFile) }
                bytes.isEmpty() -> setState { copy(error = SignInError.EmptyFile) }
                else -> {
                    setState {
                        copy(
                            objectByteCount = bytes.size,
                            decision = null,
                            error = null,
                        )
                    }
                }
            }
        }

    private fun onQrText(value: String) {
        if (_state.value.stage != Stage.Scan) return
        val tipHash = TipHashQr.parse(value)
        if (tipHash == null) {
            setState { copy(error = SignInError.NotTipHash) }
            return
        }
        setState {
            copy(
                stage = Stage.Ready,
                tipHash = tipHash,
                decision = null,
                error = null,
            )
        }
    }

    /**
     * Does not sign and does not POST the admission gate.
     * The settings row that used to call this is gone.
     */
    private fun signIn() {
        if (_state.value.signingIn) return
        setState { copy(signingIn = false, decision = null, error = null) }
    }
}
