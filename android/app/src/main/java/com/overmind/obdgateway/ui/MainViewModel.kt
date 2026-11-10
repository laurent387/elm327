package com.overmind.obdgateway.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.overmind.obdgateway.OvermindApp
import com.overmind.obdgateway.buffer.SessionRecord
import com.overmind.obdgateway.gateway.GatewayUiState
import com.overmind.obdgateway.gateway.ObdState
import com.overmind.obdgateway.net.UplinkState
import com.overmind.obdgateway.service.GatewayService
import com.overmind.obdgateway.settings.GatewaySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BtDevice(val name: String, val address: String, val looksLikeElm: Boolean)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OvermindApp

    val gateway: StateFlow<GatewayUiState> = app.controller.state
    val uplink: StateFlow<UplinkState> = app.drainer.state
    val settings: StateFlow<GatewaySettings> = app.settings.settings

    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _btEnabled = MutableStateFlow<Boolean?>(null)
    val btEnabled = _btEnabled.asStateFlow()
    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions = _sessions.asStateFlow()

    val sessionActive: Boolean
        get() = gateway.value.obdState !in setOf(ObdState.IDLE, ObdState.STOPPED)

    /** Véhicule en mouvement d'après la dernière vitesse OBD valide. */
    val vehicleMoving: Boolean
        get() = gateway.value.live["speed_kmh"]?.let { it.status == "ok" && (it.value ?: 0.0) > 0.0 } == true

    @SuppressLint("MissingPermission")
    fun refreshDevices(hasPermission: Boolean) {
        if (!hasPermission) { _devices.value = emptyList(); return }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        _btEnabled.value = adapter?.isEnabled
        _devices.value = runCatching {
            adapter?.bondedDevices.orEmpty().map {
                val n = it.name ?: it.address
                BtDevice(n, it.address, Regex("OBD|ELM|V-LINK|VLINK|VGATE|KONNWEI|OBDII", RegexOption.IGNORE_CASE).containsMatchIn(n))
            }.sortedByDescending { it.looksLikeElm }
        }.getOrDefault(emptyList())
    }

    fun selectDevice(d: BtDevice) = app.settings.update { it.copy(btAddress = d.address, btName = d.name) }

    fun saveSettings(s: GatewaySettings) {
        app.settings.update { s }
        viewModelScope.launch(Dispatchers.IO) { app.drainer.reset() } // déconnexion réseau : hors thread UI
    }

    fun start() = GatewayService.start(app)
    fun stop() = GatewayService.stop(app)
    fun sync() = GatewayService.sync(app)
    fun readDtc() = app.controller.requestDtcRead()

    fun refreshSessions() {
        viewModelScope.launch(Dispatchers.IO) { _sessions.value = app.sessions.recent(30) }
    }
}
