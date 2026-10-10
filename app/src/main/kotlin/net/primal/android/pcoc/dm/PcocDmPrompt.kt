package net.primal.android.pcoc.dm

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import net.primal.android.R

/** Shown while a matching DM is waiting. Approve enrols. Dismiss stores nothing. */
@Composable
fun PcocDmPrompt(inbox: PcocDmInbox) {
    val pending by inbox.pending.collectAsState()
    if (pending == null) return
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
