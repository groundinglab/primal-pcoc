package net.primal.android.pcoc.dm

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import net.primal.android.R

/** Shown while a matching DM is waiting, or when a CBOR map did not parse as a PCOC. */
@Composable
fun PcocDmPrompt(inbox: PcocDmInbox) {
    val pending by inbox.pending.collectAsState()
    val problem by inbox.problem.collectAsState()
    val failed = problem
    when {
        pending != null -> PcocDmOfferDialog(inbox)
        failed != null -> PcocDmParseDialog(message = failed, onClose = inbox::clearProblem)
    }
}

@Composable
private fun PcocDmOfferDialog(inbox: PcocDmInbox) {
    AlertDialog(
        onDismissRequest = { inbox.dismiss() },
        title = { Text(text = stringResource(id = R.string.pcoc_dm_title)) },
        text = { Text(text = stringResource(id = R.string.pcoc_dm_body)) },
        confirmButton = {
            TextButton(onClick = { inbox.approve(savedAtEpochMs = System.currentTimeMillis()) }) {
                Text(text = stringResource(id = R.string.pcoc_dm_approve))
            }
        },
        dismissButton = {
            TextButton(onClick = { inbox.dismiss() }) {
                Text(text = stringResource(id = R.string.pcoc_dm_dismiss))
            }
        },
    )
}

@Composable
private fun PcocDmParseDialog(message: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(text = message) },
        text = { Text(text = stringResource(id = R.string.pcoc_dm_parse_body)) },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(text = stringResource(id = R.string.pcoc_dm_dismiss))
            }
        },
    )
}
