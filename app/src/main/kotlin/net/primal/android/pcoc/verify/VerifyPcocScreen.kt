package net.primal.android.pcoc.verify

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.primal.android.R
import net.primal.android.pcoc.PcocCopyScreen

@Composable
fun VerifyPcocScreen(onClose: () -> Unit) {
    PcocCopyScreen(
        title = stringResource(id = R.string.pcoc_verify_title),
        body = stringResource(id = R.string.pcoc_verify_body),
        onClose = onClose,
    )
}
