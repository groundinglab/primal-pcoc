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
    private val poster: AdmissionDoorClient,
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

    /**
     * An opened https link is the sign-in. Skip the welcome and use the same parser as scan and paste.
     * A blank intent, the navigation route, or the bare sign-in page is not a code, so the camera stays up.
     */
    private fun openAppLink(raw: String) {
        if (_state.value.submitting) return
        when (val input = classifyLink(raw, LinkSource.AppLink)) {
            LinkInput.Ignore -> Unit
            is LinkInput.Ready -> {
                setState { copy(showWelcome = false) }
                publish(SignInNotices.scan(ready = true))
                startSubmit(input.link)
            }
            is LinkInput.Rejected -> {
                setState { copy(showWelcome = false) }
                stopScan(problem = input.problem, why = input.why)
            }
        }
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
                    is UiEvent.QrCodeDetected -> onEnteredLink(event.value)
                    is UiEvent.CodePasted -> onEnteredLink(event.value)
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
        setState { copy(scanning = true, origin = null, scanProblem = null, localStop = null, notices = emptyList()) }
    }

    private fun onDontShowAgain(checked: Boolean) {
        welcome.setDontShowAgain(checked)
        welcomeStore.setSkipWelcome(welcome.skipWelcome)
        setState { copy(dontShowAgain = welcome.skipWelcome) }
    }

    private fun onEnteredLink(raw: String) {
        val current = _state.value
        if (current.showWelcome || !current.scanning || current.submitting) return
        when (val input = classifyLink(raw, LinkSource.Entered)) {
            LinkInput.Ignore -> Unit
            is LinkInput.Ready -> {
                publish(SignInNotices.scan(ready = true))
                startSubmit(input.link)
            }
            is LinkInput.Rejected -> stopScan(problem = input.problem, why = input.why)
        }
    }

    private fun stopScan(problem: ScanProblem, why: String) {
        publish(SignInNotices.scan(ready = false, why = why)) {
            copy(scanning = false, origin = null, scanProblem = problem, localStop = null, notices = emptyList())
        }
    }

    private fun startSubmit(link: SignInLink) {
        val expired = SignInNotices.gate("expired")
        if (expired != null && signInLinkExpired(link.exp, System.currentTimeMillis())) {
            publish(expired) {
                copy(
                    scanning = false,
                    origin = link.origin,
                    submitting = false,
                    scanProblem = null,
                    localStop = null,
                )
            }
            return
        }
        setState {
            copy(scanning = false, origin = link.origin, submitting = true, scanProblem = null, localStop = null)
        }
        viewModelScope.launch {
            val userId = activeAccountStore.activeUserId()
            when (val outcome = submitCaught(link = link, userId = userId)) {
                is SubmitResult.Done -> finishSubmit(link, outcome.outcome)
                SubmitResult.Unreadable -> setState {
                    copy(submitting = false, localStop = LocalStop.ObjectUnreadable)
                }
            }
        }
    }

    private suspend fun finishSubmit(link: SignInLink, outcome: AdmissionSubmitOutcome) {
        publishAll(SignInNotices.trail(outcome)) {
            withOutcome(outcome).copy(submitting = false)
        }
        if (outcome !is AdmissionSubmitOutcome.Submitted) return
        val token = withContext(dispatchers.io()) { poster.readResult(link.nonceHex) }
        val gateNotice = token?.let(SignInNotices::gate) ?: return
        publish(gateNotice)
    }

    private fun publish(notice: SignInNotice, reducer: UiState.() -> UiState = { this }) {
        publishAll(listOf(notice), reducer)
    }

    private fun publishAll(batch: List<SignInNotice>, reducer: UiState.() -> UiState = { this }) {
        batch.forEach(SignInLog::write)
        val lines = batch.map { it.userText }
        setState {
            val next = reducer()
            val base = if (next.notices !== notices) next.notices else notices
            next.copy(notices = base + lines)
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
        AdmissionSubmitOutcome.SigningFailed -> couldNotSubmit(CouldNotSubmitReason.SigningFailed)
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
