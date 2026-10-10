package net.primal.android.pcoc.dm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import net.primal.android.R

@Singleton
class AndroidPcocDmNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : PcocDmNotifier {
    override fun show() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.pcoc_dm_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.pcoc_dm_title))
            .setContentText(context.getString(R.string.pcoc_dm_notification))
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.pcoc_dm_approve), pending(ACTION_APPROVE, REQUEST_APPROVE))
            .addAction(0, context.getString(R.string.pcoc_dm_dismiss), pending(ACTION_DISMISS, REQUEST_DISMISS))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun clear() {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun pending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PcocDmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CHANNEL_ID = "pcoc-dm"
        const val NOTIFICATION_ID = 41502
        const val REQUEST_APPROVE = 1
        const val REQUEST_DISMISS = 2
    }
}

const val ACTION_APPROVE = "net.primal.android.pcoc.dm.APPROVE"
const val ACTION_DISMISS = "net.primal.android.pcoc.dm.DISMISS"
