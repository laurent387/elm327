package com.overmind.obdgateway.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.overmind.obdgateway.BuildConfig
import com.overmind.obdgateway.MainActivity
import com.overmind.obdgateway.OvermindApp
import com.overmind.obdgateway.R
import com.overmind.obdgateway.gateway.ObdState
import com.overmind.obdgateway.obd.transport.BluetoothSppTransport
import com.overmind.obdgateway.obd.transport.ObdTransport
import com.overmind.obdgateway.obd.transport.TcpTransport
import com.overmind.obdgateway.settings.AdapterTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Service au premier plan (type connectedDevice) : garde la session ELM327 et l'envoi MQTT
 * actifs écran éteint, avec un WakeLock partiel limité à la session. À l'arrêt, il continue
 * à vider l'outbox puis s'arrête seul.
 */
class GatewayService : LifecycleService() {

    private val app get() = application as OvermindApp
    private var sessionJob: Job? = null
    private var drainJob: Job? = null
    private var healthJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val startedAt = SystemClock.elapsedRealtime()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var armed = false // ne s'arrête seul qu'après une commande START/SYNC

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif = buildNotification("Démarrage…")
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        drainJob = lifecycleScope.launch { app.drainer.run() }
        registerNetworkCallback()
        lifecycleScope.launch {
            app.controller.state.combine(app.drainer.state) { g, u -> g.obdState to u }
                .distinctUntilChanged()
                .collect { (obd, up) ->
                    val net = if (up.connected) "Overmind connecté" else "Overmind hors ligne"
                    notify("${obd.label} · $net · ${up.pending} en attente")
                    if (armed && sessionJob == null && up.pending == 0 && obd in setOf(ObdState.STOPPED, ObdState.IDLE)) stopSelf()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> { armed = true; startSession() }
            ACTION_STOP -> { armed = true; stopSession() }
            ACTION_SYNC -> { armed = true; app.drainer.wake(); if (sessionJob == null && app.outbox.count() == 0) stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (sessionJob?.isActive == true) return
        val s = app.settings.settings.value
        acquireWakeLock()
        sessionJob = lifecycleScope.launch {
            try {
                app.controller.runSession(s) { transportFor(s) }
            } finally {
                releaseWakeLock()
            }
        }
        healthJob = lifecycleScope.launch {
            while (true) {
                app.controller.publishHealth(healthFields())
                delay(30_000)
            }
        }
    }

    private fun stopSession() {
        sessionJob?.cancel(); sessionJob = null
        healthJob?.cancel(); healthJob = null
        app.drainer.wake()
    }

    @SuppressLint("MissingPermission")
    private fun transportFor(s: com.overmind.obdgateway.settings.GatewaySettings): ObdTransport = when (s.adapterTransport) {
        AdapterTransport.TCP -> TcpTransport(s.tcpHost, s.tcpPort)
        AdapterTransport.BLUETOOTH -> {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IllegalStateException("Bluetooth indisponible sur cet appareil")
            BluetoothSppTransport(adapter, s.btAddress)
        }
    }

    private fun healthFields(): Map<String, Any?> {
        val bm = getSystemService(BatteryManager::class.java)
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular+vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi+vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val g = app.controller.state.value
        return mapOf(
            "app_version" to BuildConfig.VERSION_NAME,
            "obd_state" to g.obdState.name.lowercase(),
            "bt_connected" to (g.obdState == ObdState.RUNNING),
            "network" to network,
            "outbox_pending" to app.outbox.count(),
            "battery_pct" to bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "elm_voltage_v" to g.elm?.voltage,
            "cycle_ms" to g.cycleMs,
            "uptime_s" to (SystemClock.elapsedRealtime() - startedAt) / 1000,
        )
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { app.drainer.reset() }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "overmind:obd-session").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // borne de sécurité : 12 h
        }
    }

    private fun releaseWakeLock() { runCatching { wakeLock?.release() }; wakeLock = null }

    override fun onDestroy() {
        stopSession()
        drainJob?.cancel()
        releaseWakeLock()
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Session de diagnostic", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Overmind OBD Gateway")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL = "obd_session"
        private const val NOTIF_ID = 42
        const val ACTION_START = "com.overmind.obdgateway.START"
        const val ACTION_STOP = "com.overmind.obdgateway.STOP"
        const val ACTION_SYNC = "com.overmind.obdgateway.SYNC"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GatewayService::class.java).setAction(ACTION_START))
        }
        /** Vide l'outbox hors session (rejeu des données bufferisées). */
        fun sync(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GatewayService::class.java).setAction(ACTION_SYNC))
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GatewayService::class.java).setAction(ACTION_STOP))
        }
    }
}
