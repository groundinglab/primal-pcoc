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
import net.primal.android.pcoc.admit.RequestAdmissionContract.CouldNotSubmitReason
import net.primal.android.pcoc.admit.RequestAdmissionContract.LocalStop
import net.primal.android.pcoc.admit.RequestAdmissionContract.ScanProblem
import net.primal.android.pcoc.admit.RequestAdmissionContract.Sheet
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
    poster: AdmissionDoorClient,
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

    /** An app link is the sign-in. Skip the welcome and use the same parser as scan and paste. */
    private fun openAppLink(raw: String) {
        setState { copy(showWelcome = false) }
        onLinkText(raw)
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
                    is UiEvent.QrCodeDetected -> onLinkText(event.value)
                    is UiEvent.CodePasted -> onLinkText(event.value)
                    is UiEvent.AppLink -> openAppLink(event.value)
                    UiEvent.ScanAgain -> onScanAgain()
                    is UiEvent.DontShowAgainChanged -> onDontShowAgain(event.checked)
                    UiEvent.EnterSignIn -> setState { copy(showWelcome = false) }
                }
            }
        }
    }

    private fun onScanAgain() {
        val current = _state.value
        if (current.submitting || current.sheet != null) return
        setState { copy(scanning = true, origin = null, scanProblem = null, localStop = null) }
    }

    private fun onDontShowAgain(checked: Boolean) {
        welcome.setDontShowAgain(checked)
        welcomeStore.setSkipWelcome(welcome.skipWelcome)
        setState { copy(dontShowAgain = welcome.skipWelcome) }
    }

    private fun onLinkText(raw: String) {
        val current = _state.value
        if (current.showWelcome || !current.scanning || current.submitting) return
        when (val parsed = SignInLinks.parse(raw)) {
            is SignInLinkParse.Ready -> startSubmit(parsed.link)
            SignInLinkParse.RetiredCode -> stopScan(ScanProblem.RetiredCode)
            SignInLinkParse.NotASignInLink -> stopScan(ScanProblem.NotASignInLink)
        }
    }

    private fun stopScan(problem: ScanProblem) {
        setState { copy(scanning = false, origin = null, scanProblem = problem, localStop = null) }
    }

    private fun startSubmit(link: SignInLink) {
        setState {
            copy(scanning = false, origin = link.origin, submitting = true, scanProblem = null, localStop = null)
        }
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            val outcome = submitCaught(link = link, userId = userId)
            setState {
                when (outcome) {
                    is SubmitResult.Done -> withOutcome(outcome.outcome)
                    SubmitResult.Unreadable -> copy(localStop = LocalStop.ObjectUnreadable)
                }.copy(submitting = false)
            }
        }
    }

    private suspend fun submitCaught(link: SignInLink, userId: String): SubmitResult {
        var key: ByteArray? = null
        return try {
            withContext(dispatchers.io()) {
                key = producerPrivateKey(credentialsStore, userId)
                SubmitResult.Done(request.submit(link = link, accountHex = userId, privateKey = key))
            }
        } catch (error: IOException) {
            Napier.w(error) { "Sign-in could not read the saved PCOC." }
            SubmitResult.Unreadable
        } finally {
            key?.fill(0)
        }
    }

    private sealed interface SubmitResult {
        data class Done(val outcome: AdmissionSubmitOutcome) : SubmitResult

        data object Unreadable : SubmitResult
    }
}

private fun UiState.withOutcome(outcome: AdmissionSubmitOutcome): UiState =
    when (outcome) {
        AdmissionSubmitOutcome.Submitted -> copy(sheet = Sheet.Submitted)
        AdmissionSubmitOutcome.BadSignature -> couldNotSubmit(CouldNotSubmitReason.BadSignature)
        AdmissionSubmitOutcome.Malformed -> couldNotSubmit(CouldNotSubmitReason.Malformed)
        is AdmissionSubmitOutcome.DeliveryFailed -> couldNotSubmit(outcome.failure.toReason())
        AdmissionSubmitOutcome.NoObject -> copy(hasEnrolledObject = false, localStop = LocalStop.NoObject)
        AdmissionSubmitOutcome.WrongLogin -> copy(canSign = false, localStop = LocalStop.WrongLogin)
        is AdmissionSubmitOutcome.KeyMismatch -> copy(
            localStop = LocalStop.KeyMismatch(loginPrefix = outcome.loginPrefix, filePrefix = outcome.filePrefix),
        )
        AdmissionSubmitOutcome.ObjectKeyUnreadable -> copy(localStop = LocalStop.ObjectKeyUnreadable)
    }

private fun UiState.couldNotSubmit(reason: CouldNotSubmitReason): UiState = copy(sheet = Sheet.CouldNotSubmit(reason))

private fun DeliveryFailure.toReason(): CouldNotSubmitReason =
    when (this) {
        DeliveryFailure.NoConnection -> CouldNotSubmitReason.NoConnection
        DeliveryFailure.TimedOut -> CouldNotSubmitReason.TimedOut
        DeliveryFailure.Redirected -> CouldNotSubmitReason.Redirected
        DeliveryFailure.UnexpectedResponse -> CouldNotSubmitReason.UnexpectedResponse
        DeliveryFailure.NotSent -> CouldNotSubmitReason.NotSent
    }
