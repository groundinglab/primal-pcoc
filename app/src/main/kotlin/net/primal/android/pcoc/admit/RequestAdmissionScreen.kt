package net.primal.android.pcoc.admit

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.core.utils.pasteText
import net.primal.android.pcoc.PcocScaffold
import net.primal.android.pcoc.admit.RequestAdmissionContract.CouldNotSubmitReason
import net.primal.android.pcoc.admit.RequestAdmissionContract.LocalStop
import net.primal.android.pcoc.admit.RequestAdmissionContract.ScanProblem
import net.primal.android.pcoc.admit.RequestAdmissionContract.Sheet
import net.primal.android.pcoc.admit.RequestAdmissionContract.UiEvent
import net.primal.android.pcoc.admit.RequestAdmissionContract.UiState
import net.primal.android.theme.AppTheme
import net.primal.core.utils.runCatching

/**
 * [onClose] is the top-bar back while scanning.
 * [onCloseToMenu] is Close on the acknowledgement sheet: it always lands on the PCOC menu.
 */
@Composable
fun RequestAdmissionScreen(
    viewModel: RequestAdmissionViewModel,
    onClose: () -> Unit,
    onCloseToMenu: () -> Unit,
    appLink: String? = null,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(appLink) {
        if (!appLink.isNullOrEmpty()) {
            viewModel.setEvent(UiEvent.AppLink(appLink))
        }
    }
    RequestAdmissionScreen(
        state = state,
        onClose = onClose,
        onCloseToMenu = onCloseToMenu,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@Composable
private fun RequestAdmissionScreen(
    state: UiState,
    onClose: () -> Unit,
    onCloseToMenu: () -> Unit,
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
        state.sheet?.let { sheet ->
            AdmissionSheet(sheet = sheet, onClose = onCloseToMenu)
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
        when {
            !state.hasEnrolledObject && state.scanning -> Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                text = stringResource(id = R.string.pcoc_admit_need_pcoc),
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
            state.scanning -> {
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
                    enabled = !state.submitting,
                    onPaste = { eventPublisher(UiEvent.CodePasted(it)) },
                )
            }
            else -> AdmissionLinkResult(
                modifier = Modifier.weight(1f),
                state = state,
                eventPublisher = eventPublisher,
            )
        }
    }
}

@Composable
private fun AdmissionLinkResult(
    state: UiState,
    eventPublisher: (UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        if (state.origin != null) {
            Text(
                text = stringResource(id = R.string.pcoc_admit_origin_label),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
            )
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = state.origin,
                style = AppTheme.typography.titleLarge,
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
        val problem = state.scanProblem?.text() ?: state.localStop?.text()
        if (problem != null) {
            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = problem,
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
        }
        if (!state.submitting && state.sheet == null) {
            TextButton(
                modifier = Modifier.padding(top = 8.dp),
                onClick = { eventPublisher(UiEvent.ScanAgain) },
            ) {
                Text(text = stringResource(id = R.string.pcoc_admit_scan_again))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdmissionSheet(sheet: Sheet, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        tonalElevation = 0.dp,
        containerColor = AppTheme.extraColorScheme.surfaceVariantAlt2,
        contentColor = AppTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = sheet.title(),
                style = AppTheme.typography.titleLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = sheet.body(),
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
            PrimalLoadingButton(
                modifier = Modifier
                    .padding(top = 24.dp, bottom = 16.dp)
                    .fillMaxWidth()
                    .height(56.dp),
                text = stringResource(id = R.string.pcoc_admit_close),
                enabled = true,
                loading = false,
                onClick = onClose,
            )
        }
    }
}

@Composable
private fun Sheet.title(): String =
    when (this) {
        Sheet.Submitted -> stringResource(id = R.string.pcoc_admit_submitted_title)
        is Sheet.CouldNotSubmit -> stringResource(id = R.string.pcoc_admit_could_not_submit_title)
    }

@Composable
private fun Sheet.body(): String =
    when (this) {
        Sheet.Submitted -> stringResource(id = R.string.pcoc_admit_submitted_body)
        is Sheet.CouldNotSubmit -> reason.text()
    }

@Composable
private fun CouldNotSubmitReason.text(): String =
    when (this) {
        CouldNotSubmitReason.BadSignature -> stringResource(id = R.string.pcoc_admit_reason_bad_signature)
        CouldNotSubmitReason.Malformed -> stringResource(id = R.string.pcoc_admit_reason_malformed)
        CouldNotSubmitReason.NoConnection -> stringResource(id = R.string.pcoc_admit_reason_no_connection)
        CouldNotSubmitReason.TimedOut -> stringResource(id = R.string.pcoc_admit_reason_timed_out)
        CouldNotSubmitReason.Redirected -> stringResource(id = R.string.pcoc_admit_reason_redirected)
        CouldNotSubmitReason.UnexpectedResponse -> stringResource(id = R.string.pcoc_admit_reason_unexpected)
        CouldNotSubmitReason.NotSent -> stringResource(id = R.string.pcoc_admit_reason_not_sent)
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

@Composable
private fun ScanProblem.text(): String =
    when (this) {
        ScanProblem.NotASignInLink -> stringResource(id = R.string.pcoc_admit_problem_not_link)
        ScanProblem.RetiredCode -> stringResource(id = R.string.pcoc_admit_problem_retired)
    }

@Composable
private fun LocalStop.text(): String =
    when (this) {
        LocalStop.NoObject -> stringResource(id = R.string.pcoc_admit_need_pcoc)
        LocalStop.WrongLogin -> stringResource(id = R.string.pcoc_admit_wrong_login)
        is LocalStop.KeyMismatch -> mismatchCopy(loginPrefix = loginPrefix, filePrefix = filePrefix)
        LocalStop.ObjectKeyUnreadable -> stringResource(id = R.string.pcoc_admit_key_unreadable)
        LocalStop.ObjectUnreadable -> stringResource(id = R.string.pcoc_admit_object_unreadable)
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
