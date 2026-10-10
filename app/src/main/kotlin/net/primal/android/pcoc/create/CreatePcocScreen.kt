package net.primal.android.pcoc.create

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
import net.primal.android.pcoc.verify.thousands
import net.primal.android.theme.AppTheme

@Composable
fun CreatePcocScreen(viewModel: CreatePcocViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    CreatePcocScreen(
        state = state,
        onClose = onClose,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@Composable
internal fun CreatePcocScreen(
    state: CreateUiState,
    onClose: () -> Unit,
    eventPublisher: (CreateUiEvent) -> Unit,
) {
    PcocScaffold(title = stringResource(id = R.string.pcoc_create_title), onClose = onClose) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            when (state.phase) {
                ProducePhase.Idle -> IdleBody(state = state, eventPublisher = eventPublisher)
                ProducePhase.Running, ProducePhase.Settling -> RunningBody(
                    state = state,
                    eventPublisher = eventPublisher,
                )
                ProducePhase.Written -> WrittenBody(state = state, eventPublisher = eventPublisher)
                ProducePhase.Stopped, ProducePhase.Failed -> StoppedBody(state = state, eventPublisher = eventPublisher)
            }
        }
    }
}

@Composable
private fun IdleBody(state: CreateUiState, eventPublisher: (CreateUiEvent) -> Unit) {
    Text(
        text = stringResource(id = R.string.pcoc_create_idle_title),
        style = AppTheme.typography.titleLarge,
        color = AppTheme.colorScheme.onPrimary,
    )
    SectionLabel(text = stringResource(id = R.string.pcoc_create_public_key))
    Text(
        text = state.npub ?: stringResource(id = R.string.pcoc_create_no_key),
        style = AppTheme.typography.bodyLarge,
        color = AppTheme.colorScheme.onPrimary,
    )
    SectionLabel(text = stringResource(id = R.string.pcoc_create_gossip))
    Text(
        text = stringResource(id = R.string.pcoc_create_gossip_body),
        style = AppTheme.typography.bodyLarge,
        color = AppTheme.colorScheme.onPrimary,
    )
    Text(
        modifier = Modifier.padding(top = 16.dp),
        text = stringResource(id = R.string.pcoc_create_body),
        style = AppTheme.typography.bodyMedium,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
    )
    PrimalLoadingButton(
        modifier = Modifier
            .padding(top = 24.dp)
            .fillMaxWidth()
            .height(56.dp),
        text = stringResource(id = R.string.pcoc_create_start),
        enabled = state.npub != null,
        loading = false,
        onClick = { eventPublisher(CreateUiEvent.Start) },
    )
}

@Composable
private fun RunningBody(state: CreateUiState, eventPublisher: (CreateUiEvent) -> Unit) {
    if (state.phase == ProducePhase.Settling) {
        Text(
            text = stringResource(id = R.string.pcoc_create_settling),
            style = AppTheme.typography.titleLarge,
            color = AppTheme.colorScheme.onPrimary,
        )
    } else {
        CircularProgressIndicator()
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = stringResource(id = R.string.pcoc_create_running),
            style = AppTheme.typography.titleLarge,
            color = AppTheme.colorScheme.onPrimary,
        )
    }
    Metric(
        label = stringResource(id = R.string.pcoc_create_stage),
        value = stringResource(
            id = R.string.pcoc_create_stage_value,
            state.stage,
            state.stagesClosed,
            HIKE_STAGES,
        ),
    )
    Metric(
        label = stringResource(id = R.string.pcoc_create_legs),
        value = thousands(state.legsTotal),
    )
    state.tipHeight?.let { height ->
        Metric(label = stringResource(id = R.string.pcoc_create_tip), value = thousands(height))
    }
    headerEstimate(state.stagesClosed)?.let { estimate ->
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = estimate,
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
        )
    }
    Text(
        modifier = Modifier.padding(top = 8.dp),
        text = stringResource(id = R.string.pcoc_create_stop_note),
        style = AppTheme.typography.bodyMedium,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
    )
    if (state.outputPath.isNotEmpty()) {
        Metric(label = stringResource(id = R.string.pcoc_create_path), value = state.outputPath)
    }
    LogBlock(lines = state.logLines)
    TextButton(onClick = { eventPublisher(CreateUiEvent.Stop) }) {
        Text(text = stringResource(id = R.string.pcoc_create_stop))
    }
}

@Composable
private fun WrittenBody(state: CreateUiState, eventPublisher: (CreateUiEvent) -> Unit) {
    Text(
        text = stringResource(id = R.string.pcoc_create_written_title),
        style = AppTheme.typography.titleLarge,
        color = AppTheme.colorScheme.onPrimary,
    )
    Text(
        modifier = Modifier.padding(top = 8.dp),
        text = stringResource(id = R.string.pcoc_create_written_body),
        style = AppTheme.typography.bodyLarge,
        color = AppTheme.colorScheme.onPrimary,
    )
    Metric(
        label = stringResource(id = R.string.pcoc_create_legs),
        value = thousands(state.legsTotal),
    )
    state.objectBytes?.let { bytes ->
        Metric(label = stringResource(id = R.string.pcoc_verify_row_bytes), value = thousands(bytes))
    }
    state.objectSha256?.let { sha ->
        Metric(label = stringResource(id = R.string.pcoc_verify_row_sha256), value = sha)
    }
    if (state.outputPath.isNotEmpty()) {
        Metric(label = stringResource(id = R.string.pcoc_create_path), value = state.outputPath)
    }
    Text(
        modifier = Modifier.padding(top = 12.dp),
        text = stringResource(
            id = if (state.savedForAccount) {
                R.string.pcoc_create_saved
            } else {
                R.string.pcoc_create_not_saved
            },
        ),
        style = AppTheme.typography.bodyLarge,
        color = AppTheme.colorScheme.onPrimary,
    )
    TextButton(onClick = { eventPublisher(CreateUiEvent.Reset) }) {
        Text(text = stringResource(id = R.string.pcoc_create_again))
    }
}

@Composable
private fun StoppedBody(state: CreateUiState, eventPublisher: (CreateUiEvent) -> Unit) {
    val text = if (state.phase == ProducePhase.Failed && !state.message.isNullOrBlank()) {
        stringResource(id = R.string.pcoc_create_failed, state.message)
    } else {
        stringResource(id = R.string.pcoc_create_stopped)
    }
    Text(
        text = text,
        style = AppTheme.typography.bodyLarge,
        color = AppTheme.colorScheme.onPrimary,
    )
    TextButton(onClick = { eventPublisher(CreateUiEvent.Reset) }) {
        Text(text = stringResource(id = R.string.pcoc_create_again))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        modifier = Modifier.padding(top = 16.dp),
        text = text,
        style = AppTheme.typography.bodyMedium,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
    )
}

@Composable
private fun Metric(label: String, value: String) {
    SectionLabel(text = label)
    Text(
        text = value,
        style = AppTheme.typography.bodyLarge,
        color = AppTheme.colorScheme.onPrimary,
    )
}

@Composable
private fun LogBlock(lines: List<String>) {
    if (lines.isEmpty()) return
    SectionLabel(text = stringResource(id = R.string.pcoc_create_log))
    Text(
        text = lines.takeLast(8).joinToString("\n"),
        style = AppTheme.typography.bodyMedium,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
    )
}
