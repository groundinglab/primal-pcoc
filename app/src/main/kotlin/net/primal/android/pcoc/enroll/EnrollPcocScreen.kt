package net.primal.android.pcoc.enroll

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import net.primal.android.R
import net.primal.android.core.compose.ConfirmActionAlertDialog
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.pcoc.PcocScaffold
import net.primal.android.pcoc.enroll.EnrollPcocContract.Notice
import net.primal.android.pcoc.enroll.EnrollPcocContract.UiEvent
import net.primal.android.pcoc.enroll.EnrollPcocContract.UiState
import net.primal.android.theme.AppTheme

@Composable
fun EnrollPcocScreen(viewModel: EnrollPcocViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    EnrollPcocScreen(
        state = state,
        onClose = onClose,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@Composable
private fun EnrollPcocScreen(
    state: UiState,
    onClose: () -> Unit,
    eventPublisher: (UiEvent) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) eventPublisher(UiEvent.FilePicked(uri))
    }
    PcocScaffold(
        title = stringResource(id = R.string.pcoc_enroll_title),
        onClose = onClose,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(
                    id = if (state.stored) {
                        R.string.pcoc_enroll_stored_status
                    } else {
                        R.string.pcoc_enroll_empty_status
                    },
                ),
                style = AppTheme.typography.titleLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
            if (state.stored) {
                StoredFileLines(fileName = state.fileName, savedAtEpochMs = state.savedAtEpochMs)
            }
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = stringResource(id = R.string.pcoc_enroll_hint),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
            )
            val notice = state.notice
            if (notice != null) {
                Text(
                    modifier = Modifier.padding(top = 16.dp),
                    text = notice.text(),
                    style = AppTheme.typography.bodyLarge,
                    color = AppTheme.colorScheme.onPrimary,
                )
            }
            PrimalLoadingButton(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .height(56.dp),
                text = stringResource(id = R.string.pcoc_enroll_choose_file),
                enabled = !state.busy,
                loading = state.busy,
                onClick = { picker.launch(arrayOf("*/*")) },
            )
            if (state.stored) {
                PrimalLoadingButton(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(56.dp),
                    text = stringResource(id = R.string.pcoc_enroll_clear),
                    enabled = !state.busy,
                    onClick = { eventPublisher(UiEvent.RequestClear) },
                )
            }
        }
    }
    if (state.confirmClear) {
        ConfirmActionAlertDialog(
            dialogTitle = stringResource(id = R.string.pcoc_enroll_clear_title),
            dialogText = stringResource(id = R.string.pcoc_enroll_clear_text),
            confirmText = stringResource(id = R.string.pcoc_enroll_clear_confirm),
            dismissText = stringResource(id = R.string.pcoc_enroll_clear_dismiss),
            onConfirmation = { eventPublisher(UiEvent.ConfirmClear) },
            onDismissRequest = { eventPublisher(UiEvent.DismissClear) },
        )
    }
}

@Composable
private fun StoredFileLines(fileName: String?, savedAtEpochMs: Long?) {
    if (!fileName.isNullOrBlank()) {
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = fileName,
            style = AppTheme.typography.bodyLarge,
            color = AppTheme.colorScheme.onPrimary,
        )
    }
    if (savedAtEpochMs != null) {
        val savedOn = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(savedAtEpochMs))
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = stringResource(id = R.string.pcoc_enroll_saved_date, savedOn),
            style = AppTheme.typography.bodyLarge,
            color = AppTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun Notice.text(): String =
    when (this) {
        Notice.Stored -> stringResource(id = R.string.pcoc_enroll_outcome_stored)
        Notice.Unreadable -> stringResource(id = R.string.pcoc_enroll_outcome_unreadable)
        Notice.NotStored -> stringResource(id = R.string.pcoc_enroll_outcome_not_stored)
        Notice.Cleared -> stringResource(id = R.string.pcoc_enroll_outcome_cleared)
    }
