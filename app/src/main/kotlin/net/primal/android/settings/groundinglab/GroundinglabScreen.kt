package net.primal.android.settings.groundinglab

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.button.PrimalLoadingButton
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.core.utils.ellipsizeMiddle
import net.primal.android.scanner.QrCodeScanner
import net.primal.android.scanner.domain.QrCodeDataType
import net.primal.android.settings.groundinglab.GroundinglabContract.SignInError
import net.primal.android.settings.groundinglab.GroundinglabContract.Stage
import net.primal.android.settings.groundinglab.GroundinglabContract.UiEvent
import net.primal.android.settings.groundinglab.GroundinglabContract.UiState
import net.primal.android.theme.AppTheme
import net.primal.android.theme.domain.PrimalTheme

@Composable
fun GroundinglabScreen(viewModel: GroundinglabViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    GroundinglabScreen(
        state = state,
        onClose = onClose,
        eventPublisher = viewModel::setEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroundinglabScreen(
    state: UiState,
    onClose: () -> Unit,
    eventPublisher: (UiEvent) -> Unit,
) {
    if (state.stage == Stage.Scan) {
        TipHashScanStage(
            error = state.error,
            onClose = { eventPublisher(UiEvent.CloseScanner) },
            onQrText = { eventPublisher(UiEvent.QrTextDetected(it)) },
        )
    } else {
        ReadyStage(
            state = state,
            onClose = onClose,
            eventPublisher = eventPublisher,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyStage(
    state: UiState,
    onClose: () -> Unit,
    eventPublisher: (UiEvent) -> Unit,
) {
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            eventPublisher(UiEvent.ImportFile(uri))
        }
    }

    PrimalScaffold(
        topBar = {
            PrimalTopAppBar(
                title = stringResource(id = R.string.settings_groundinglab_title),
                navigationIcon = PrimalIcons.ArrowBack,
                navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                onNavigationIconClick = onClose,
            )
        },
        content = { paddingValues ->
            GroundinglabForm(
                modifier = Modifier
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                state = state,
                onImport = { openDocument.launch(arrayOf("*/*")) },
                eventPublisher = eventPublisher,
            )
        },
    )
}

@Composable
private fun GroundinglabForm(
    state: UiState,
    onImport: () -> Unit,
    eventPublisher: (UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(id = R.string.settings_groundinglab_intro),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
        )
        PrimalLoadingButton(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(id = R.string.settings_groundinglab_import),
            onClick = onImport,
        )
        Text(
            text = if (state.objectByteCount != null) {
                stringResource(id = R.string.settings_groundinglab_object_ready, state.objectByteCount)
            } else {
                stringResource(id = R.string.settings_groundinglab_object_missing)
            },
            style = AppTheme.typography.bodyMedium,
        )
        PrimalLoadingButton(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(id = R.string.settings_groundinglab_scan),
            onClick = { eventPublisher(UiEvent.OpenScanner) },
        )
        Text(
            text = state.tipHash?.let { hash ->
                stringResource(id = R.string.settings_groundinglab_tip_hash, hash.ellipsizeMiddle(size = 8))
            } ?: stringResource(id = R.string.settings_groundinglab_tip_hash_missing),
            style = AppTheme.typography.bodyMedium,
        )
        PrimalLoadingButton(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(id = R.string.settings_groundinglab_sign_in),
            enabled = state.canSignIn,
            loading = state.signingIn,
            onClick = { eventPublisher(UiEvent.SignIn) },
        )
        DecisionText(decision = state.decision)
        state.error?.let { error ->
            Text(
                text = error.message(),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DecisionText(decision: String?) {
    if (decision == null) return
    Text(
        text = stringResource(id = R.string.settings_groundinglab_decision_label),
        style = AppTheme.typography.bodySmall,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
    )
    Text(
        text = decision,
        style = AppTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (decision == SignInPayload.ACCEPT) {
            AppTheme.colorScheme.primary
        } else {
            AppTheme.colorScheme.error
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TipHashScanStage(
    error: SignInError?,
    onClose: () -> Unit,
    onQrText: (String) -> Unit,
) {
    var isClosing by remember { mutableStateOf(false) }
    BackHandler {
        isClosing = true
        onClose()
    }
    PrimalScaffold(
        topBar = {
            PrimalTopAppBar(
                title = stringResource(id = R.string.settings_groundinglab_scan_title),
                navigationIcon = PrimalIcons.ArrowBack,
                navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                onNavigationIconClick = {
                    isClosing = true
                    onClose()
                },
            )
        },
        content = { paddingValues ->
            QrCodeScanner(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                cameraVisible = !isClosing,
                onQrCodeDetected = { result ->
                    if (result.type == QrCodeDataType.TIP_HASH) {
                        onQrText(result.value)
                    } else {
                        onQrText("")
                    }
                },
                hint = {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        modifier = Modifier.padding(horizontal = 32.dp),
                        text = error?.message() ?: stringResource(id = R.string.settings_groundinglab_scan_hint),
                        style = AppTheme.typography.bodyLarge,
                        color = if (error != null) {
                            AppTheme.colorScheme.error
                        } else {
                            AppTheme.extraColorScheme.onSurfaceVariantAlt2
                        },
                    )
                },
            )
        },
    )
}

@Composable
private fun SignInError.message(): String {
    return when (this) {
        SignInError.EmptyFile -> stringResource(id = R.string.settings_groundinglab_error_empty_file)
        SignInError.UnreadableFile -> stringResource(id = R.string.settings_groundinglab_error_read)
        SignInError.NotTipHash -> stringResource(id = R.string.settings_groundinglab_error_not_tip_hash)
        SignInError.ProducerKeyRequired -> stringResource(id = R.string.settings_groundinglab_error_producer_key)
        SignInError.EmptyBody -> stringResource(id = R.string.settings_groundinglab_error_empty_body)
        SignInError.MissingDecision -> stringResource(id = R.string.settings_groundinglab_error_missing_decision)
        SignInError.Transport -> stringResource(id = R.string.settings_groundinglab_error_transport)
    }
}

private const val PREVIEW_OBJECT_BYTES = 128
private const val PREVIEW_TIP_HASH_HEX_LENGTH = 32

@Preview
@Composable
private fun PreviewGroundinglabReady() {
    PrimalPreview(primalTheme = PrimalTheme.Ice) {
        GroundinglabScreen(
            state = UiState(
                objectByteCount = PREVIEW_OBJECT_BYTES,
                tipHash = "ab".repeat(PREVIEW_TIP_HASH_HEX_LENGTH),
                decision = SignInPayload.ACCEPT,
            ),
            onClose = {},
            eventPublisher = {},
        )
    }
}
