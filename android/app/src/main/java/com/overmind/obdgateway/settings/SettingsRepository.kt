package com.overmind.obdgateway.settings

import android.content.Context
import com.overmind.obdgateway.telemetry.SequenceSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("gateway_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<GatewaySettings> = _settings.asStateFlow()

    private fun load(): GatewaySettings {
        val d = GatewaySettings()
        return GatewaySettings(
            networkMode = runCatching { NetworkMode.valueOf(prefs.getString("networkMode", d.networkMode.name)!!) }.getOrDefault(d.networkMode),
            brokerHost = prefs.getString("brokerHost", d.brokerHost)!!,
            brokerPort = prefs.getInt("brokerPort", d.brokerPort),
            useTls = prefs.getBoolean("useTls", d.useTls),
            username = prefs.getString("username", d.username)!!,
            password = SecretStore.decrypt(prefs.getString("passwordEnc", "")!!),
            caCertPem = prefs.getString("caCertPem", d.caCertPem)!!,
            vehicleId = prefs.getString("vehicleId", d.vehicleId)!!,
            gatewayId = prefs.getString("gatewayId", d.gatewayId)!!,
            engineType = prefs.getString("engineType", d.engineType)!!,
            aspiration = prefs.getString("aspiration", d.aspiration)!!,
            mapSensorLocation = prefs.getString("mapSensorLocation", d.mapSensorLocation)!!,
            pollIntervalMs = prefs.getLong("pollIntervalMs", d.pollIntervalMs),
            dtcIntervalS = prefs.getLong("dtcIntervalS", d.dtcIntervalS),
            adapterTransport = runCatching { AdapterTransport.valueOf(prefs.getString("adapterTransport", d.adapterTransport.name)!!) }.getOrDefault(d.adapterTransport),
            btAddress = prefs.getString("btAddress", d.btAddress)!!,
            btName = prefs.getString("btName", d.btName)!!,
            tcpHost = prefs.getString("tcpHost", d.tcpHost)!!,
            tcpPort = prefs.getInt("tcpPort", d.tcpPort),
            multiPid = prefs.getBoolean("multiPid", d.multiPid),
        )
    }

    fun update(transform: (GatewaySettings) -> GatewaySettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("networkMode", s.networkMode.name)
            .putString("brokerHost", s.brokerHost.trim())
            .putInt("brokerPort", s.brokerPort)
            .putBoolean("useTls", s.useTls)
            .putString("username", s.username.trim())
            .putString("passwordEnc", SecretStore.encrypt(s.password))
            .putString("caCertPem", s.caCertPem.trim())
            .putString("vehicleId", s.vehicleId.trim())
            .putString("gatewayId", s.gatewayId.trim())
            .putString("engineType", s.engineType)
            .putString("aspiration", s.aspiration)
            .putString("mapSensorLocation", s.mapSensorLocation)
            .putLong("pollIntervalMs", s.pollIntervalMs)
            .putLong("dtcIntervalS", s.dtcIntervalS)
            .putString("adapterTransport", s.adapterTransport.name)
            .putString("btAddress", s.btAddress)
            .putString("btName", s.btName)
            .putString("tcpHost", s.tcpHost.trim())
            .putInt("tcpPort", s.tcpPort)
            .putBoolean("multiPid", s.multiPid)
            .apply()
        _settings.value = s
    }
}

/**
 * Compteur de séquence monotone et persistant (survit aux redémarrages de l'appli).
 * Réserve des blocs de 256 pour éviter une écriture disque par message : après un arrêt
 * brutal la séquence saute, mais ne recule jamais.
 */
class PrefsSequence(context: Context) : SequenceSource {
    private val prefs = context.getSharedPreferences("gateway_sequence", Context.MODE_PRIVATE)
    private var value = prefs.getLong("reserved", 0L)
    private var reserved = value
    @Synchronized override fun next(): Long {
        value += 1
        if (value > reserved) {
            reserved = value + 255
            prefs.edit().putLong("reserved", reserved).commit()
        }
        return value
    }
}
