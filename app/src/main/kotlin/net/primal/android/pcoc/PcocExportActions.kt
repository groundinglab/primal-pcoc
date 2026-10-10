package net.primal.android.pcoc

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.primal.android.R
import net.primal.android.pcoc.create.CreateUiState
import net.primal.android.theme.AppTheme

@Composable
internal fun rememberProduceExport(
    current: () -> CreateUiState,
): (PcocExportKind, PcocExportAction) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(current)
    val saveObject = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PCOC_OBJECT_MIME),
    ) { uri ->
        if (uri != null) scope.launch { writeExport(context, latest.value(), PcocExportKind.Object, uri) }
    }
    val saveLog = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PCOC_LOG_MIME),
    ) { uri ->
        if (uri != null) scope.launch { writeExport(context, latest.value(), PcocExportKind.Log, uri) }
    }
    return { kind, action ->
        val state = latest.value()
        when (action) {
            PcocExportAction.Save -> when (kind) {
                PcocExportKind.Object -> saveObject.launch(objectExportName(state.outputPath))
                PcocExportKind.Log -> saveLog.launch(logExportName(state.outputPath))
            }
            PcocExportAction.Share -> scope.launch { shareExport(context, state, kind) }
        }
    }
}

@Composable
internal fun rememberStoredObjectExport(
    load: suspend () -> PcocStoredExport?,
): (PcocExportAction) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(load)
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PCOC_OBJECT_MIME),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val export = latest.value() ?: return@launch
                writeStoredExport(context, export, uri)
            }
        }
    }
    return { action ->
        scope.launch {
            val export = latest.value() ?: return@launch
            when (action) {
                PcocExportAction.Save -> save.launch(export.displayName)
                PcocExportAction.Share -> shareStoredExport(context, export)
            }
        }
    }
}

@Composable
internal fun PcocExportActions(
    showObject: Boolean,
    showLog: Boolean,
    onExport: (PcocExportKind, PcocExportAction) -> Unit,
) {
    if (showObject) {
        ExportChoice(
            title = stringResource(id = R.string.pcoc_export_object),
            onSave = { onExport(PcocExportKind.Object, PcocExportAction.Save) },
            onShare = { onExport(PcocExportKind.Object, PcocExportAction.Share) },
        )
    }
    if (showLog) {
        ExportChoice(
            title = stringResource(id = R.string.pcoc_export_log),
            onSave = { onExport(PcocExportKind.Log, PcocExportAction.Save) },
            onShare = { onExport(PcocExportKind.Log, PcocExportAction.Share) },
        )
    }
}

@Composable
private fun ExportChoice(title: String, onSave: () -> Unit, onShare: () -> Unit) {
    Text(
        modifier = Modifier.padding(top = 16.dp),
        text = title,
        style = AppTheme.typography.bodyMedium,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
    )
    Row {
        TextButton(onClick = onSave) {
            Text(text = stringResource(id = R.string.pcoc_export_save))
        }
        TextButton(onClick = onShare) {
            Text(text = stringResource(id = R.string.pcoc_export_share))
        }
    }
}
