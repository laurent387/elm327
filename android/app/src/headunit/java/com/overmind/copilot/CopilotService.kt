package com.overmind.copilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Service au premier plan « micro » : l'écoute du mot de réveil continue écran éteint. */
class CopilotService : LifecycleService() {
    private val runtime get() = CopilotRuntime.get(this)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Copilote Kitt", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, notification("Démarrage…"),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        runtime.start()
        lifecycleScope.launch {
            runtime.ui.map { it.state.label }.distinctUntilChanged().collect { label ->
                nm.notify(NOTIF_ID, notification(label))
            }
        }
    }

    override fun onDestroy() { runtime.stop(); super.onDestroy() }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, CopilotActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kitt")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "kitt"
        private const val NOTIF_ID = 42
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, CopilotService::class.java))
    }
}
