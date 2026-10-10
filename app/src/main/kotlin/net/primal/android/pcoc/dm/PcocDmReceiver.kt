package net.primal.android.pcoc.dm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import jakarta.inject.Inject

@AndroidEntryPoint
class PcocDmReceiver : BroadcastReceiver() {
    @Inject
    lateinit var inbox: PcocDmInbox

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_APPROVE -> inbox.approve(savedAtEpochMs = System.currentTimeMillis())
            ACTION_DISMISS -> inbox.dismiss()
        }
    }
}
