package net.primal.android.pcoc.admit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aakira.napier.Napier
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.pcoc.admit.RequestAdmissionContract.Outcome
import net.primal.android.pcoc.admit.RequestAdmissionContract.ScanProblem
import net.primal.android.pcoc.admit.RequestAdmissionContract.UiEvent
import net.primal.android.pcoc.admit.RequestAdmissionContract.UiState
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.credentials.CredentialsStore
import net.primal.core.utils.coroutines.DispatcherProvider

@HiltViewModel
class RequestAdmissionViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val credentialsStore: CredentialsStore,
    private val store: EnrolledPcocStore,
    poster: AdmissionSiteClient,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val request = RequestServiceAdmission(store = store, poster = poster)

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
            combine(
                activeAccountStore.activeUserId,
                credentialsStore.credentials,
            ) { userId, _ -> userId }
                .collect { userId -> refreshAccount(userId) }
        }
    }

    private suspend fun refreshAccount(userId: String) {
        val stored = withContext(dispatchers.io()) { store.contains(userId) }
        val key = withContext(dispatchers.io()) { producerPrivateKey(credentialsStore, userId) }
        key?.fill(0)
        setState { copy(hasEnrolledObject = stored, canSign = key != null) }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is UiEvent.QrCodeDetected -> onQr(event.value)
                    UiEvent.ScanAgain -> if (!_state.value.submitting) {
                        setState {
                            copy(
                                scanning = true,
                                confirming = false,
                                readyUrl = null,
                                hashHex = null,
                                scanProblem = null,
                                outcome = null,
                            )
                        }
                    }
                    UiEvent.RequestSign -> if (canConfirm()) {
                        setState { copy(confirming = true) }
                    }
                    UiEvent.DismissSign -> setState { copy(confirming = false) }
                    UiEvent.SignAndSubmit -> {
                        setState { copy(confirming = false) }
                        signAndSubmit()
                    }
                }
            }
        }
    }

    private fun onQr(raw: String) {
        if (!_state.value.scanning) return
        val parsed = AdmissionQr.parse(raw)
        val ready = parsed as? AdmissionQrParse.Ready
        setState {
            copy(
                scanning = false,
                outcome = null,
                readyUrl = ready?.exactUrl,
                hashHex = ready?.hashHex,
                scanProblem = parsed.toScanProblem(),
            )
        }
    }

    private fun canConfirm(): Boolean {
        val current = _state.value
        return current.hasEnrolledObject &&
            current.canSign &&
            current.readyUrl != null &&
            !current.submitting
    }

    private fun signAndSubmit() {
        val current = _state.value
        val url = current.readyUrl
        val blocked = current.submitting || url == null
        val cannotSign = !current.hasEnrolledObject || !current.canSign
        if (blocked || cannotSign) return
        setState { copy(submitting = true, outcome = null) }
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            val outcome = submitCaught(exactUrl = url, userId = userId)
            try {
                refreshAccount(userId)
            } catch (error: IOException) {
                Napier.w(error) { "Service admission account refresh failed." }
            } finally {
                setState {
                    copy(
                        submitting = false,
                        outcome = outcome.toOutcome(),
                    )
                }
            }
        }
    }

    private suspend fun submitCaught(exactUrl: String, userId: String): AdmissionSubmitOutcome {
        var key: ByteArray? = null
        return try {
            key = withContext(dispatchers.io()) { producerPrivateKey(credentialsStore, userId) }
            if (key == null) {
                AdmissionSubmitOutcome.NotSubmitted
            } else {
                val signingKey = key
                withContext(dispatchers.io()) {
                    request.submit(exactUrl = exactUrl, accountHex = userId, privateKey = signingKey)
                }
            }
        } catch (error: IOException) {
            Napier.w(error) { "Service admission request failed." }
            AdmissionSubmitOutcome.Failed
        } catch (error: IllegalArgumentException) {
            Napier.w(error) { "Service admission request failed." }
            AdmissionSubmitOutcome.Failed
        } catch (error: IllegalStateException) {
            Napier.w(error) { "Service admission request failed." }
            AdmissionSubmitOutcome.Failed
        } finally {
            key?.fill(0)
        }
    }
}

private fun AdmissionQrParse.toScanProblem(): ScanProblem? =
    when (this) {
        is AdmissionQrParse.Ready -> null
        AdmissionQrParse.NotHttps -> ScanProblem.NotHttps
        AdmissionQrParse.BareHash -> ScanProblem.BareHash
        AdmissionQrParse.MissingOrBadHash -> ScanProblem.BadHash
        AdmissionQrParse.GateUrl -> ScanProblem.GateUrl
        AdmissionQrParse.NotAServiceUrl -> ScanProblem.NotAServiceUrl
    }

private fun AdmissionSubmitOutcome.toOutcome(): Outcome? =
    when (this) {
        is AdmissionSubmitOutcome.Decision -> Outcome.Decided(
            accepted = decision == AdmissionDecision.Accept,
            verifierRan = verifierRan,
        )
        AdmissionSubmitOutcome.Failed -> Outcome.Failed
        AdmissionSubmitOutcome.NotSubmitted -> null
    }
