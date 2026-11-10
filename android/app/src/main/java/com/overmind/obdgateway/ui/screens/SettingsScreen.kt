package com.overmind.obdgateway.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overmind.obdgateway.settings.AdapterTransport
import com.overmind.obdgateway.settings.GatewaySettings
import com.overmind.obdgateway.settings.NetworkMode
import com.overmind.obdgateway.ui.MainViewModel
import com.overmind.obdgateway.ui.theme.OvermindColors

@Composable
private fun Field(label: String, value: String, enabled: Boolean, number: Boolean = false, secret: Boolean = false, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled, singleLine = singleLine,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = OvermindColors.Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (v, l) -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(l) }, enabled = enabled) }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val saved by vm.settings.collectAsState()
    val g by vm.gateway.collectAsState()
    var s by remember(saved) { mutableStateOf(saved) }
    // Réglages réservés à l'arrêt : verrouillés si le véhicule roule ou si une session est active.
    val moving = vm.vehicleMoving
    val editable = !moving && !vm.sessionActive
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (moving) Text("Véhicule en mouvement : réglages verrouillés.", color = OvermindColors.Amber, fontSize = 16.sp)
        else if (vm.sessionActive) Text("Arrêtez la session pour modifier les réglages.", color = OvermindColors.Amber)

        Section("Serveur Overmind (MQTT)") {
            Choice("Mode réseau", listOf(NetworkMode.TAILSCALE to "Tailscale (recommandé)", NetworkMode.DIRECT to "Internet direct"), s.networkMode, editable) {
                s = s.copy(networkMode = it, brokerPort = if (it == NetworkMode.DIRECT && s.brokerPort == 1883) 8883 else s.brokerPort)
            }
            Field("Adresse du serveur", s.brokerHost, editable) { s = s.copy(brokerHost = it) }
            Field("Port", s.brokerPort.toString(), editable, number = true) { v -> v.toIntOrNull()?.let { s = s.copy(brokerPort = it) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = s.effectiveTls, onCheckedChange = { s = s.copy(useTls = it) }, enabled = editable && s.networkMode == NetworkMode.TAILSCALE)
                Text("  TLS" + if (s.networkMode == NetworkMode.DIRECT) " (obligatoire en Internet direct)" else "")
            }
            Field("Identifiant MQTT", s.username, editable) { s = s.copy(username = it) }
            Field("Mot de passe MQTT", s.password, editable, secret = true) { s = s.copy(password = it) }
            Field("Certificat CA (PEM, facultatif)", s.caCertPem, editable, singleLine = false) { s = s.copy(caCertPem = it) }
        }
        Section("Véhicule") {
            Field("Identifiant véhicule", s.vehicleId, editable) { s = s.copy(vehicleId = it.lowercase()) }
            Field("Identifiant passerelle", s.gatewayId, editable) { s = s.copy(gatewayId = it.lowercase()) }
            Choice("Motorisation", listOf("petrol" to "Essence", "diesel" to "Diesel", "unknown" to "?"), s.engineType, editable) { s = s.copy(engineType = it) }
            Choice("Admission", listOf("na" to "Atmo", "turbo" to "Turbo", "supercharger" to "Compresseur", "unknown" to "?"), s.aspiration, editable) { s = s.copy(aspiration = it) }
            Choice(
                "Capteur MAP",
                listOf("post_compressor" to "Après compr.", "intake_manifold" to "Collecteur", "unknown" to "Inconnu"),
                s.mapSensorLocation, editable,
            ) { s = s.copy(mapSensorLocation = it) }
        }
        Section("Acquisition") {
            Field("Période d'acquisition (ms)", s.pollIntervalMs.toString(), editable, number = true) { v -> v.toLongOrNull()?.let { s = s.copy(pollIntervalMs = it) } }
            Field("Lecture des défauts toutes les (s)", s.dtcIntervalS.toString(), editable, number = true) { v -> v.toLongOrNull()?.let { s = s.copy(dtcIntervalS = it) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = s.multiPid, onCheckedChange = { s = s.copy(multiPid = it) }, enabled = editable)
                Text("  Requêtes multi-PID (CAN, désactivées seules si non supportées)")
            }
            Choice("Liaison ELM327", listOf(AdapterTransport.BLUETOOTH to "Bluetooth", AdapterTransport.TCP to "TCP / Wi-Fi"), s.adapterTransport, editable) {
                s = s.copy(adapterTransport = it)
            }
            if (s.adapterTransport == AdapterTransport.TCP) {
                Field("Hôte ELM (Wi-Fi ou simulateur)", s.tcpHost, editable) { s = s.copy(tcpHost = it) }
                Field("Port ELM", s.tcpPort.toString(), editable, number = true) { v -> v.toIntOrNull()?.let { s = s.copy(tcpPort = it) } }
            }
        }
        s.validate().forEach { Text("• $it", color = OvermindColors.Amber, fontSize = 13.sp) }
        Button(onClick = { vm.saveSettings(s) }, enabled = editable && s != saved, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer") }
        OutlinedButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("Optimisation batterie (fonctionnement écran éteint)") }
        Text(
            "Les valeurs par défaut (overmind.example.ts.net, slk_r171, android_01) sont des exemples à remplacer.",
            color = OvermindColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
        )
        if (g.obdState.name == "RUNNING") Text("Session active : ${g.sessionId}", color = OvermindColors.Muted, fontSize = 11.sp)
    }
}
