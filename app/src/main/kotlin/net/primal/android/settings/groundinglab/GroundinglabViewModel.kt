package net.primal.android.settings.groundinglab

import android.content.ContentResolver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.acinq.secp256k1.Hex
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
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.credentials.CredentialsStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.fold
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.cryptography.utils.toNpub

@HiltViewModel
class GroundinglabViewModel @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val contentResolver: ContentResolver,
    private val activeAccountStore: ActiveAccountStore,
    private val credentialsStore: CredentialsStore,
    private val admissionGateClient: AdmissionGateClient,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private fun setState(reducer: UiState.() -> UiState) = _state.getAndUpdate { it.reducer() }

    private val events = MutableSharedFlow<UiEvent>()
    fun setEvent(event: UiEvent) = viewModelScope.launch { events.emit(event) }

    /** CBOR held for `object_b64`. The local yielded-public-key compare is not done here. */
    private var objectBytes: ByteArray? = null

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
                    objectBytes = bytes
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

    private fun signIn() {
        val bytes = objectBytes
        val tipHash = _state.value.tipHash
        if (bytes == null || tipHash == null || _state.value.signingIn) return

        viewModelScope.launch {
            setState { copy(signingIn = true, decision = null, error = null) }
            val privateKey = withContext(dispatcherProvider.io()) { loadProducerPrivateKey() }
            if (privateKey == null) {
                setState { copy(signingIn = false, error = SignInError.ProducerKeyRequired) }
                return@launch
            }
            val outcome = withContext(dispatcherProvider.io()) {
                runCatching {
                    val signature = TipHashSigner.sign(privateKey = privateKey, tipHashHex = tipHash)
                    admissionGateClient.signIn(objectBytes = bytes, signatureHex = signature)
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is AdmissionSignInResult.Decision ->
                            setState { copy(signingIn = false, decision = result.decision, error = null) }

                        AdmissionSignInResult.EmptyBody ->
                            setState { copy(signingIn = false, error = SignInError.EmptyBody) }

                        AdmissionSignInResult.MissingDecision ->
                            setState { copy(signingIn = false, error = SignInError.MissingDecision) }

                        AdmissionSignInResult.TransportFailure ->
                            setState { copy(signingIn = false, error = SignInError.Transport) }
                    }
                },
                onFailure = { error ->
                    val mapped = if (error is IllegalArgumentException) {
                        SignInError.NotTipHash
                    } else {
                        SignInError.Transport
                    }
                    setState { copy(signingIn = false, error = mapped) }
                },
            )
        }
    }

    /**
     * The logged-in producer nsec. Does not create or read the internal signer.
     */
    private fun loadProducerPrivateKey(): ByteArray? {
        val userId = activeAccountStore.activeUserId()
        if (userId.isBlank()) return null
        return runCatching {
            val npub = Hex.decode(userId).toNpub()
            credentialsStore.findOrThrow(npub = npub).producerPrivateKeyOrNull()
        }.getOrNull()
    }
}
