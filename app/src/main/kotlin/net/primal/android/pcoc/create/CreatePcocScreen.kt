package net.primal.android.pcoc.create

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.primal.android.R
import net.primal.android.pcoc.PcocCopyScreen

@Composable
fun CreatePcocScreen(onClose: () -> Unit) {
    PcocCopyScreen(
        title = stringResource(id = R.string.pcoc_create_title),
        body = stringResource(id = R.string.pcoc_create_body),
        onClose = onClose,
    )
}
