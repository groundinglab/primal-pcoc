package net.primal.android.pcoc.admit

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.ConfirmActionAlertDialog
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.core.utils.pasteText
import net.primal.android.pcoc.PcocScaffold
import net.primal.android.pcoc.admit.RequestAdmissionContract.Outcome
import net.primal.android.pcoc.admit.RequestAdmissionContract.ScanProblem
import net.primal.android.pcoc.admit.RequestAdmissionContract.UiEvent
import net.primal.android.pcoc.admit.RequestAdmissionContract.UiState
import net.primal.android.theme.AppTheme
import net.primal.core.utils.runCatching

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
        if (state.showWelcome) {
            SignInWelcome(
                modifier = Modifier.padding(paddingValues),
                dontShowAgain = state.dontShowAgain,
                onDontShowAgainChange = { eventPublisher(UiEvent.DontShowAgainChanged(it)) },
                onEnter = { eventPublisher(UiEvent.EnterSignIn) },
            )
        } else {
            AdmissionSignInBody(
                state = state,
                eventPublisher = eventPublisher,
                modifier = Modifier.padding(paddingValues),
            )
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

@Composable
private fun AdmissionSignInBody(
    state: UiState,
    eventPublisher: (UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
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
            AdmissionPasteButton(
                enabled = !state.readingObject && !state.submitting,
                onPaste = { eventPublisher(UiEvent.CodePasted(it)) },
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
}

@Composable
private fun SignInWelcome(
    dontShowAgain: Boolean,
    onDontShowAgainChange: (Boolean) -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(id = R.string.pcoc_admit_welcome_need),
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = stringResource(id = R.string.pcoc_admit_welcome_code),
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = stringResource(id = R.string.pcoc_admit_welcome_cost),
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .clickable { onDontShowAgainChange(!dontShowAgain) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = dontShowAgain,
                onCheckedChange = null,
            )
            Text(
                text = stringResource(id = R.string.pcoc_admit_welcome_skip),
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
        }
        PrimalLoadingButton(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .fillMaxWidth()
                .height(56.dp),
            text = stringResource(id = R.string.pcoc_admit_welcome_cta),
            enabled = true,
            loading = false,
            onClick = onEnter,
        )
    }
}

@Composable
private fun AdmissionPasteButton(enabled: Boolean, onPaste: (String) -> Unit) {
    val context = LocalContext.current
    PrimalLoadingButton(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .fillMaxWidth()
            .height(56.dp),
        text = stringResource(id = R.string.pcoc_admit_paste),
        enabled = enabled,
        loading = false,
        onClick = { onPaste(clipboardText(context)) },
    )
}

private fun clipboardText(context: Context): String = runCatching { context.pasteText() }.getOrNull().orEmpty()

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
                style = if (state.submitting || state.outcome.isPostResult()) {
                    AppTheme.typography.titleLarge
                } else {
                    AppTheme.typography.bodyLarge
                },
                color = if (state.outcome.isPostError()) {
                    AppTheme.colorScheme.error
                } else {
                    AppTheme.colorScheme.onPrimary
                },
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
        ScanProblem.PasteRejected -> stringResource(id = R.string.pcoc_admit_paste_error)
    }

private fun Outcome?.isPostResult(): Boolean =
    this is Outcome.Delivered || this is Outcome.BadSignature || this is Outcome.Failed

private fun Outcome?.isPostError(): Boolean =
    this is Outcome.BadSignature || this is Outcome.Failed

@Composable
private fun Outcome.text(): String =
    when (this) {
        Outcome.Delivered -> stringResource(id = R.string.pcoc_admit_delivered)
        Outcome.BadSignature -> stringResource(id = R.string.pcoc_admit_bad_signature)
        Outcome.Failed -> stringResource(id = R.string.pcoc_admit_failed)
        is Outcome.KeyMismatch -> mismatchCopy(loginPrefix = loginPrefix, filePrefix = filePrefix)
        Outcome.ObjectKeyUnreadable -> stringResource(id = R.string.pcoc_admit_key_unreadable)
        Outcome.NotSent -> stringResource(id = R.string.pcoc_admit_not_sent)
    }

@Composable
private fun mismatchCopy(loginPrefix: String, filePrefix: String): String {
    val base = stringResource(id = R.string.pcoc_admit_key_mismatch)
    if (!KEY_PREFIX.matches(loginPrefix) || !KEY_PREFIX.matches(filePrefix)) return base
    val detail = stringResource(
        id = R.string.pcoc_admit_key_mismatch_detail,
        loginPrefix,
        filePrefix,
    )
    return base + "\n" + detail
}
