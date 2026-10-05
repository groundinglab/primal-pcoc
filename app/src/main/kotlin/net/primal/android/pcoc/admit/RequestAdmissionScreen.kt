package net.primal.android.pcoc.admit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.ConfirmActionAlertDialog
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.pcoc.PcocScaffold
import net.primal.android.pcoc.admit.RequestAdmissionContract.Outcome
import net.primal.android.pcoc.admit.RequestAdmissionContract.ScanProblem
import net.primal.android.pcoc.admit.RequestAdmissionContract.UiEvent
import net.primal.android.pcoc.admit.RequestAdmissionContract.UiState
import net.primal.android.theme.AppTheme

@Composable
fun RequestAdmissionScreen(viewModel: RequestAdmissionViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    RequestAdmissionScreen(
        state = state,
        onClose = onClose,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@Composable
private fun RequestAdmissionScreen(
    state: UiState,
    onClose: () -> Unit,
    eventPublisher: (UiEvent) -> Unit,
) {
    PcocScaffold(
        title = stringResource(id = R.string.pcoc_admit_title),
        onClose = onClose,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (!state.hasEnrolledObject) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    text = stringResource(id = R.string.pcoc_admit_need_pcoc),
                    style = AppTheme.typography.bodyLarge,
                    color = AppTheme.colorScheme.onPrimary,
                )
            } else if (state.scanning) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    text = stringResource(id = R.string.pcoc_admit_hint),
                    style = AppTheme.typography.bodyMedium,
                    color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
                )
                AdmissionQrScanner(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    onQrText = { eventPublisher(UiEvent.QrCodeDetected(it)) },
                )
            } else {
                AdmissionScanResult(
                    modifier = Modifier.weight(1f),
                    state = state,
                    eventPublisher = eventPublisher,
                )
            }
            if (state.offerSignIn()) {
                PrimalLoadingButton(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .fillMaxWidth()
                        .height(56.dp),
                    text = stringResource(id = R.string.pcoc_admit_sign_in),
                    enabled = !state.submitting,
                    loading = false,
                    onClick = { eventPublisher(UiEvent.RequestSign) },
                )
            }
        }
        if (state.confirming && !state.submitting) {
            ConfirmActionAlertDialog(
                dialogTitle = stringResource(id = R.string.pcoc_admit_confirm_title),
                confirmText = stringResource(id = R.string.pcoc_admit_confirm_ok),
                dismissText = stringResource(id = R.string.pcoc_admit_confirm_cancel),
                onConfirmation = { eventPublisher(UiEvent.SignAndSubmit) },
                onDismissRequest = { eventPublisher(UiEvent.DismissSign) },
            )
        }
    }
}

private fun UiState.offerSignIn(): Boolean =
    hasEnrolledObject &&
        canSign &&
        readyUrl != null &&
        scanProblem == null &&
        outcome == null &&
        !scanning &&
        !confirming &&
        !submitting &&
        !readingObject

@Composable
private fun AdmissionScanResult(
    state: UiState,
    eventPublisher: (UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        val headline = state.headline()
        if (headline != null) {
            Text(
                text = headline,
                style = if (state.submitting || state.outcome is Outcome.FromGate) {
                    AppTheme.typography.titleLarge
                } else {
                    AppTheme.typography.bodyLarge
                },
                color = AppTheme.colorScheme.onPrimary,
            )
        }
        if (state.submitting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .size(28.dp),
                color = AppTheme.colorScheme.onPrimary,
            )
        }
        TextButton(
            onClick = { eventPublisher(UiEvent.ScanAgain) },
            enabled = !state.submitting && !state.readingObject,
        ) {
            Text(text = stringResource(id = R.string.pcoc_admit_scan_again))
        }
    }
}

@Composable
private fun UiState.headline(): String? =
    when {
        submitting -> stringResource(id = R.string.pcoc_admit_sending)
        scanProblem != null -> scanProblem.text()
        outcome != null -> outcome.text()
        !canSign -> stringResource(id = R.string.pcoc_admit_wrong_login)
        readyUrl != null && !confirming && !readingObject -> stringResource(id = R.string.pcoc_admit_ready)
        else -> null
    }

@Composable
private fun ScanProblem.text(): String =
    when (this) {
        ScanProblem.NotHttps -> stringResource(id = R.string.pcoc_admit_problem_not_https)
        ScanProblem.BareHash -> stringResource(id = R.string.pcoc_admit_problem_bare_hash)
        ScanProblem.BadHash -> stringResource(id = R.string.pcoc_admit_problem_bad_hash)
        ScanProblem.GateUrl -> stringResource(id = R.string.pcoc_admit_problem_gate)
        ScanProblem.NotAServiceUrl -> stringResource(id = R.string.pcoc_admit_problem_other)
    }

@Composable
private fun Outcome.text(): String =
    when (this) {
        is Outcome.FromGate -> gateResultText(decision = decision, verifierRan = verifierRan)
        Outcome.Failed -> stringResource(id = R.string.pcoc_admit_failed)
        Outcome.KeyMismatch -> stringResource(id = R.string.pcoc_admit_key_mismatch)
        Outcome.ObjectKeyUnreadable -> stringResource(id = R.string.pcoc_admit_key_unreadable)
        Outcome.NotSent -> stringResource(id = R.string.pcoc_admit_not_sent)
    }
