package net.primal.android.pcoc.create

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.pcoc.PcocScaffold
import net.primal.android.theme.AppTheme

/** On-device produce is not in this build. This screen states what a run will need. */
@Composable
fun CreatePcocScreen(onClose: () -> Unit) {
    PcocScaffold(title = stringResource(id = R.string.pcoc_create_title), onClose = onClose) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(id = R.string.pcoc_create_soon_title),
                style = AppTheme.typography.titleLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = stringResource(id = R.string.pcoc_create_body),
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onPrimary,
            )
        }
    }
}
