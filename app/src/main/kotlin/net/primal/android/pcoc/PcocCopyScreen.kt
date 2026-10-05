package net.primal.android.pcoc

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.primal.android.theme.AppTheme

@Composable
fun PcocCopyScreen(title: String, body: String, onClose: () -> Unit) {
    PcocScaffold(title = title, onClose = onClose) { paddingValues ->
        Text(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            text = body,
            style = AppTheme.typography.bodyLarge,
            color = AppTheme.colorScheme.onPrimary,
        )
    }
}
