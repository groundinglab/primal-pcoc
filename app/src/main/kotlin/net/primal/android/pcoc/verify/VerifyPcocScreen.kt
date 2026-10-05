package net.primal.android.pcoc.verify

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.pcoc.PcocScaffold
import net.primal.android.pcoc.hike.VerifyAny
import net.primal.android.pcoc.verify.VerifyPcocContract.Notice
import net.primal.android.pcoc.verify.VerifyPcocContract.Stage
import net.primal.android.pcoc.verify.VerifyPcocContract.UiEvent
import net.primal.android.pcoc.verify.VerifyPcocContract.UiState
import net.primal.android.theme.AppTheme

@Composable
fun VerifyPcocScreen(viewModel: VerifyPcocViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    VerifyPcocScreen(
        state = state,
        onClose = onClose,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@Composable
private fun VerifyPcocScreen(
    state: UiState,
    onClose: () -> Unit,
    eventPublisher: (UiEvent) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) eventPublisher(UiEvent.FilePicked(uri))
    }
    PcocScaffold(
        title = stringResource(id = R.string.pcoc_verify_title),
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
                text = stringResource(id = R.string.pcoc_verify_hint),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
            )
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = state.stageLabel(),
                    style = AppTheme.typography.bodyLarge,
                    color = AppTheme.colorScheme.onPrimary,
                )
                TextButton(onClick = { eventPublisher(UiEvent.Cancel) }) {
                    Text(text = stringResource(id = R.string.pcoc_verify_cancel))
                }
            }
            val notice = state.notice
            if (!state.busy && notice != null) {
                Text(
                    modifier = Modifier.padding(top = 16.dp),
                    text = notice.text(),
                    style = AppTheme.typography.bodyLarge,
                    color = AppTheme.colorScheme.onPrimary,
                )
            }
            val word = state.word
            if (word != null) {
                Text(
                    modifier = Modifier.padding(top = 16.dp),
                    text = word.text(),
                    style = AppTheme.typography.titleLarge,
                    color = AppTheme.colorScheme.onPrimary,
                )
            }
            PrimalLoadingButton(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .height(56.dp),
                text = stringResource(id = R.string.pcoc_verify_choose_file),
                enabled = !state.busy,
                loading = false,
                onClick = { picker.launch(arrayOf("*/*")) },
            )
        }
    }
}

@Composable
private fun UiState.stageLabel(): String =
    when (stage) {
        Stage.File, null -> stringResource(id = R.string.pcoc_verify_stage_file)
        Stage.Index -> stringResource(id = R.string.pcoc_verify_stage_index)
        Stage.Terrain -> stringResource(id = R.string.pcoc_verify_stage_terrain)
        Stage.Checks -> stringResource(id = R.string.pcoc_verify_stage_checks)
        Stage.Legs -> stringResource(
            id = R.string.pcoc_verify_stage_legs,
            legsDone,
            legsTotal,
        )
    }

@Composable
private fun Notice.text(): String =
    when (this) {
        Notice.IndexUnreachable -> stringResource(id = R.string.pcoc_verify_index_error)
        Notice.DidNotFinish -> stringResource(id = R.string.pcoc_verify_unfinished)
    }

@Composable
private fun VerifyAny.Word.text(): String =
    when (this) {
        VerifyAny.Word.VALID -> stringResource(id = R.string.pcoc_verify_valid)
        VerifyAny.Word.INVALID -> stringResource(id = R.string.pcoc_verify_invalid)
    }
