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
    private val welcomeStore: SignInWelcomeStore,
) : ViewModel() {

    private val request = RequestServiceAdmission(store = store, poster = poster)
    private val welcome = SignInWelcomeGate(skipWelcome = welcomeStore.skipWelcome())

    private val _state = MutableStateFlow(
        UiState(
            showWelcome = welcome.onOpen() == SignInWelcomeStep.Welcome,
            dontShowAgain = welcome.skipWelcome,
        ),
    )
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
        val stored = withContext(dispatchers.io()) { accountHasObject(store, userId) }
        val key = withContext(dispatchers.io()) { producerPrivateKey(credentialsStore, userId) }
        key?.fill(0)
        setState { copy(hasEnrolledObject = stored, canSign = key != null) }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            events.collect { event ->
                when (event) {
                    is UiEvent.QrCodeDetected -> onQr(event.value)
                    is UiEvent.CodePasted -> onPaste(event.value)
                    UiEvent.ScanAgain -> onScanAgain()
                    UiEvent.RequestSign -> if (canConfirm()) {
                        setState { copy(confirming = true) }
                    }
                    UiEvent.DismissSign -> if (!_state.value.submitting) {
                        setState { copy(confirming = false) }
                    }
                    UiEvent.SignAndSubmit -> startSubmit()
                    is UiEvent.DontShowAgainChanged -> onDontShowAgain(event.checked)
                    UiEvent.EnterSignIn -> setState { copy(showWelcome = false) }
                }
            }
        }
    }

    private fun onScanAgain() {
        val current = _state.value
        if (current.submitting || current.readingObject) return
        setState {
            copy(
                scanning = true,
                readingObject = false,
                confirming = false,
                readyUrl = null,
                hashHex = null,
                scanProblem = null,
                outcome = null,
            )
        }
    }

    private fun onDontShowAgain(checked: Boolean) {
        welcome.setDontShowAgain(checked)
        welcomeStore.setSkipWelcome(welcome.skipWelcome)
        setState { copy(dontShowAgain = welcome.skipWelcome) }
    }

    private fun onPaste(raw: String) {
        val current = _state.value
        if (current.showWelcome || !current.scanning || current.readingObject || current.submitting) return
        val url = (AdmissionPaste.parse(raw) as? AdmissionPasteParse.Ready)?.exactUrl
        if (url == null) {
            rejectPaste()
        } else {
            // Same `h` and submit path as scanning this URL.
            onQr(url)
        }
    }

    private fun rejectPaste() {
        setState {
            copy(
                scanning = false,
                readingObject = false,
                confirming = false,
                outcome = null,
                readyUrl = null,
                hashHex = null,
                scanProblem = ScanProblem.PasteRejected,
            )
        }
    }

    private fun onQr(raw: String) {
        val current = _state.value
        if (current.showWelcome || !current.scanning || current.readingObject || current.submitting) return
        val parsed = AdmissionQr.parse(raw)
        val ready = parsed as? AdmissionQrParse.Ready
        if (ready == null) {
            setState {
                copy(
                    scanning = false,
                    readingObject = false,
                    confirming = false,
                    outcome = null,
                    readyUrl = null,
                    hashHex = null,
                    scanProblem = parsed.toScanProblem(),
                )
            }
            return
        }
        setState {
            copy(
                readingObject = true,
                outcome = null,
                readyUrl = ready.exactUrl,
                hashHex = ready.hashHex,
                scanProblem = null,
            )
        }
        viewModelScope.launch {
            finishReadyScan(exactUrl = ready.exactUrl, hashBytes = ready.hashBytes.size)
        }
    }

    private suspend fun finishReadyScan(exactUrl: String, hashBytes: Int) {
        val userId = activeAccountStore.activeUserId()
        val plan = try {
            withContext(dispatchers.io()) { readyPlan(userId) }
        } catch (error: IOException) {
            Napier.w(error) { "Service admission could not read the saved PCOC." }
            null
        }
        if (!_state.value.readingObject) return
        if (plan == null) {
            setState {
                copy(
                    scanning = false,
                    readingObject = false,
                    confirming = false,
                    outcome = Outcome.NotSent,
                )
            }
            return
        }
        AdmissionTrace.scan(
            pubkeyMatch = plan.pubkeyMatch,
            hBytes = hashBytes,
            target = admissionTarget(exactUrl),
            followUp = plan.followUp.name,
            keys = plan.keys,
        )
        setState { afterReadyScan(plan) }
    }

    private fun canConfirm(): Boolean {
        val current = _state.value
        return current.hasEnrolledObject &&
            current.canSign &&
            current.readyUrl != null &&
            current.outcome == null &&
            !current.submitting &&
            !current.readingObject
    }

    private fun startSubmit() {
        val current = _state.value
        val url = current.readyUrl
        val blocked = !current.confirming || current.submitting || url == null
        val cannotSign = !current.hasEnrolledObject || !current.canSign
        if (blocked || cannotSign) return
        setState { copy(confirming = false, submitting = true, outcome = null) }
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

    private fun readyPlan(userId: String): ReadyScanPlan {
        val privateKey = producerPrivateKey(credentialsStore, userId)
        val canSign = privateKey != null
        val signingKey = privateKey?.let(::signingPublicKey)
        privateKey?.fill(0)
        return planReadyScan(
            canSign = canSign,
            accountHex = userId,
            objectBytes = enrolledObject(store, userId),
            signingKey = signingKey,
        )
    }
}

private fun UiState.afterReadyScan(plan: ReadyScanPlan): UiState {
    val base = copy(readingObject = false, scanProblem = null)
    return when (plan.followUp) {
        ReadyScanFollowUp.Confirm -> base.copy(scanning = false, confirming = true, outcome = null)
        ReadyScanFollowUp.WrongLogin ->
            base.copy(scanning = false, confirming = false, canSign = false, outcome = null)
        ReadyScanFollowUp.KeyMismatch ->
            base.copy(
                scanning = false,
                confirming = false,
                outcome = Outcome.KeyMismatch(
                    loginPrefix = plan.keys?.loginPrefix.orEmpty(),
                    filePrefix = plan.keys?.filePrefix.orEmpty(),
                ),
            )
        ReadyScanFollowUp.ObjectKeyUnreadable ->
            base.copy(scanning = false, confirming = false, outcome = Outcome.ObjectKeyUnreadable)
        ReadyScanFollowUp.NoObject ->
            base.copy(
                scanning = false,
                confirming = false,
                hasEnrolledObject = false,
                readyUrl = null,
                outcome = null,
            )
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

private fun AdmissionSubmitOutcome.toOutcome(): Outcome =
    when (this) {
        AdmissionSubmitOutcome.Delivered -> Outcome.Delivered
        AdmissionSubmitOutcome.BadSignature -> Outcome.BadSignature
        AdmissionSubmitOutcome.Failed -> Outcome.Failed
        is AdmissionSubmitOutcome.KeyMismatch -> Outcome.KeyMismatch(
            loginPrefix = loginPrefix,
            filePrefix = filePrefix,
        )
        AdmissionSubmitOutcome.ObjectKeyUnreadable -> Outcome.ObjectKeyUnreadable
        AdmissionSubmitOutcome.NotSubmitted -> Outcome.NotSent
    }
