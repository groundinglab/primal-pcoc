package net.primal.android.pcoc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.settings.SettingsItem
import net.primal.android.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcocScreen(onClose: () -> Unit) {
    PrimalScaffold(
        modifier = Modifier,
        topBar = {
            PrimalTopAppBar(
                title = stringResource(id = R.string.pcoc_title),
                navigationIcon = PrimalIcons.ArrowBack,
                navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                onNavigationIconClick = onClose,
            )
        },
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .background(color = AppTheme.colorScheme.surfaceVariant)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(paddingValues),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                SettingsItem(
                    headlineText = stringResource(id = R.string.pcoc_import),
                    onClick = null,
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsItem(
                    headlineText = stringResource(id = R.string.pcoc_verified),
                    onClick = null,
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsItem(
                    headlineText = stringResource(id = R.string.pcoc_produce),
                    onClick = null,
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsItem(
                    headlineText = stringResource(id = R.string.pcoc_open_qr_scanner),
                    onClick = null,
                )
            }
        },
    )
}
