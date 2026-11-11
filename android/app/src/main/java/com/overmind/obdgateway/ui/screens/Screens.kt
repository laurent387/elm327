package com.overmind.obdgateway.ui.screens

import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overmind.obdgateway.ui.LocalDisplayProfile
import com.overmind.obdgateway.gateway.ObdState
import com.overmind.obdgateway.obd.dtc.Dtc
import com.overmind.obdgateway.obd.dtc.DtcDescriptions
import com.overmind.obdgateway.obd.pid.PidCatalog
import com.overmind.obdgateway.settings.AdapterTransport
import com.overmind.obdgateway.ui.BtDevice
import com.overmind.obdgateway.ui.MainViewModel
import com.overmind.obdgateway.ui.theme.OvermindColors

@Composable
private fun Screen(content: @Composable () -> Unit) {
    val p = LocalDisplayProfile.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(p.dp(12f))) { content() }
}

@Composable
fun SessionButton(vm: MainViewModel) {
    val g by vm.gateway.collectAsState()
    val s by vm.settings.collectAsState()
    val active = g.obdState !in setOf(ObdState.IDLE, ObdState.STOPPED)
    val problems = s.validate()
    if (active) {
        Button(
            onClick = { vm.stop() }, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = OvermindColors.Bad),
        ) { Text("Arrêter la session", fontSize = 18.sp) }
    } else {
        Button(onClick = { vm.start() }, enabled = problems.isEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Démarrer la session", fontSize = 18.sp)
        }
        problems.forEach { Text("• $it", color = OvermindColors.Amber, fontSize = 13.sp) }
    }
}

// ---------------------------------------------------------------- 1. Tableau de bord
@Composable
fun DashboardScreen(vm: MainViewModel) {
    val g by vm.gateway.collectAsState()
    val up by vm.uplink.collectAsState()
    val s by vm.settings.collectAsState()
    val p = LocalDisplayProfile.current
    Screen {
        val etat: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
            Section("État") {
                StatusDot(
                    when (g.obdState) { ObdState.RUNNING -> true; ObdState.IDLE, ObdState.STOPPED -> false; else -> null },
                    "OBD : ${g.obdState.label}",
                    listOfNotNull(g.adapter, g.elm?.protocol?.label, g.lastError?.takeIf { g.obdState != ObdState.RUNNING }).joinToString(" · ").ifEmpty { null },
                )
                StatusDot(
                    up.connected, if (up.connected) "Overmind connecté" else "Overmind hors ligne",
                    "${s.brokerHost}:${s.brokerPort}${if (s.effectiveTls) " (TLS)" else ""} · ${up.pending} message(s) en attente" +
                        (up.lastError?.let { " · $it" } ?: ""),
                )
                StatusDot(true, "Véhicule : ${s.vehicleId}", g.vin?.let { "VIN $it" })
            }
            }
        }
        if (p.landscape) {
            // Paysage (autoradio) : état et bouton de session côte à côte, tout reste visible sans défiler.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                etat(Modifier.weight(2f))
                Column(Modifier.weight(1f).padding(start = 8.dp)) { SessionButton(vm) }
            }
        } else etat(Modifier.fillMaxWidth())
        // Six grandeurs : 3 colonnes si la largeur le permet (autoradio, paysage), sinon 2.
        val tiles: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> BigValue("Régime", g.live["rpm"], "rpm", m) },
            { m -> BigValue("Vitesse", g.live["speed_kmh"], "km/h", m) },
            { m -> BigValue("Liquide refr.", g.live["coolant_temp_c"], "°C", m, OvermindColors.Amber) },
            { m -> BigValue("MAP absolue", g.live["map_kpa_absolute"], "kPa", m) },
            { m -> BigValue("Charge", g.live["engine_load_pct"], "%", m) },
            { m -> BigValue("Surpression*", g.live[PidCatalog.BOOST_KEY], "kPa", m, OvermindColors.Amber) },
        )
        val cols = if (p.wide || (p.landscape && p.widthDp >= 560)) 3 else 2
        tiles.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth()) { row.forEach { it(Modifier.weight(1f)) } }
        }
        Text(
            "* MAP − BARO ; n'est une pression de suralimentation que si le capteur MAP est en aval du compresseur " +
                "(emplacement déclaré : ${s.mapSensorLocation}).",
            color = OvermindColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(4.dp),
        )
        if (!p.landscape) { Spacer(Modifier.height(8.dp)); SessionButton(vm) }
        g.cycleMs?.let { Text("Cycle d'acquisition : $it ms", color = OvermindColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(4.dp)) }
    }
}

// ---------------------------------------------------------------- 2. Connexion ELM327
@Composable
fun ConnectionScreen(vm: MainViewModel, hasBtPermission: Boolean, requestPermission: () -> Unit) {
    val ctx = LocalContext.current
    val devices by vm.devices.collectAsState()
    val btOn by vm.btEnabled.collectAsState()
    val s by vm.settings.collectAsState()
    val g by vm.gateway.collectAsState()
    LaunchedEffect(hasBtPermission) { vm.refreshDevices(hasBtPermission) }
    Screen {
        Section("Adaptateur") {
            if (s.adapterTransport == AdapterTransport.TCP) {
                Text("Mode TCP (ELM327 Wi-Fi / simulateur) : ${s.tcpHost}:${s.tcpPort}. Modifiable dans Paramètres.", color = OvermindColors.Muted)
            } else if (!hasBtPermission) {
                Text("Autorisation « Appareils à proximité » requise pour accéder au Bluetooth.", color = OvermindColors.Amber)
                Button(onClick = requestPermission) { Text("Autoriser") }
            } else {
                if (btOn == false) Text("Bluetooth désactivé.", color = OvermindColors.Amber)
                Text("Adaptateurs déjà appairés :", color = OvermindColors.Muted, fontSize = 13.sp)
                if (devices.isEmpty()) Text("Aucun appareil appairé.", color = OvermindColors.Muted)
                devices.forEach { d ->
                    val selected = d.address == s.btAddress
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !vm.sessionActive) { vm.selectDevice(d) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (selected) "◉" else "○", color = if (selected) OvermindColors.Accent else OvermindColors.Muted, fontSize = 20.sp)
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(d.name + if (d.looksLikeElm) "  · OBD" else "", fontSize = 16.sp)
                            Text(d.address, color = OvermindColors.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                // Autoradios dont le Bluetooth n'appaire pas l'ELM327 : connexion directe par adresse
                // (tentative RFCOMM « insecure », sans appairage préalable).
                var mac by remember { mutableStateOf("") }
                val macOk = MAC_RE.matches(mac.trim())
                Text("Ou adresse de l'adaptateur (ex. 86:41:DF:71:A8:26, visible dans les détails Bluetooth du téléphone) :",
                    color = OvermindColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mac, { mac = it.uppercase().take(17) }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("AA:BB:CC:DD:EE:FF") }, isError = mac.isNotEmpty() && !macOk)
                    Button(onClick = { vm.selectDevice(BtDevice("ELM327 (adresse saisie)", mac.trim(), true)); mac = "" },
                        enabled = macOk && !vm.sessionActive) { Text("Utiliser") }
                }
                if (s.btAddress.isNotEmpty() && devices.none { it.address == s.btAddress })
                    Text("Adaptateur choisi : ${s.btName} ${s.btAddress}", color = OvermindColors.Accent, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.refreshDevices(hasBtPermission) }) { Text("Actualiser") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Appairer (PIN 1234 / 0000)") }
                }
            }
        }
        Section("Diagnostic de connexion") {
            KeyValue("État", g.obdState.label)
            KeyValue("Liaison", g.adapter)
            KeyValue("Version ELM", g.elm?.version)
            KeyValue("Description", g.elm?.deviceDescription)
            KeyValue("Tension batterie", g.elm?.voltage?.let { "%.1f V".format(it) })
            KeyValue("Protocole", g.elm?.protocol?.label)
            KeyValue("En-têtes ECU", g.elm?.headers?.let { if (it) "oui" else "non" })
            KeyValue("PID supportés", if (g.supportedPids.isEmpty()) null else g.supportedPids.size.toString())
            KeyValue("VIN", g.vin)
            g.lastError?.let { Text("Dernière erreur : $it", color = OvermindColors.Bad, fontSize = 13.sp) }
        }
        SessionButton(vm)
        Section("Journal") {
            g.log.takeLast(40).reversed().forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = OvermindColors.Muted) }
        }
    }
}

// ---------------------------------------------------------------- 3. Données temps réel
@Composable
fun LiveDataScreen(vm: MainViewModel) {
    val g by vm.gateway.collectAsState()
    val rows = PidCatalog.all.mapNotNull { g.live[it.key] } + listOfNotNull(g.live[PidCatalog.BOOST_KEY])
    val (avail, missing) = rows.partition { it.status != "unsupported" }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text(
                if (g.live.isEmpty()) "Démarrez une session pour découvrir les PID du véhicule."
                else "${avail.size} grandeurs disponibles · ${missing.size} non exposées par le calculateur",
                color = OvermindColors.Muted, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(avail, key = { it.key }) { v ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.label, fontSize = 15.sp)
                    Text("${v.pid ?: "dérivé"} · ${v.ecu ?: "—"} · ${v.status}", color = OvermindColors.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    formatValue(v.value, v.unit) + " " + v.unit,
                    fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    color = if (v.status == "ok") OvermindColors.Accent else OvermindColors.Muted,
                )
            }
        }
        if (missing.isNotEmpty()) item {
            Text("Non exposés : " + missing.joinToString { it.pid ?: it.key }, color = OvermindColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

// ---------------------------------------------------------------- 4. Codes défauts
@Composable
fun DtcScreen(vm: MainViewModel) {
    val g by vm.gateway.collectAsState()
    val running = g.obdState == ObdState.RUNNING
    Screen {
        Button(onClick = { vm.readDtc() }, enabled = running && !g.dtcReading, modifier = Modifier.fillMaxWidth()) {
            Text(if (g.dtcReading) "Lecture en cours…" else "Lire les codes défauts")
        }
        if (!running) Text("Disponible pendant une session active.", color = OvermindColors.Muted, fontSize = 12.sp)
        val d = g.lastDtc
        var confirm by remember { mutableStateOf(false) }
        val codes = d?.let { (it.stored + it.pending).map { c -> c.code }.distinct() }.orEmpty()
        OutlinedButton(
            onClick = { confirm = true },
            enabled = running && !g.dtcReading && !g.dtcClearing && d != null && (codes.isNotEmpty() || d.milOn == true),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text(if (g.dtcClearing) "Effacement en cours…" else "Effacer les codes défauts") }
        g.dtcClearResult?.let { r ->
            Text(r, color = if (r.startsWith("Défauts effacés")) OvermindColors.Ok else OvermindColors.Amber,
                fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            "Effacement uniquement depuis cet écran, après confirmation, véhicule à l'arrêt et moteur coupé (contact mis). " +
                "Jamais à distance. L'interprétation est faite par l'agent Overmind Mechanic.",
            color = OvermindColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Effacer les codes défauts ?") },
            text = {
                Text(
                    (if (codes.isNotEmpty()) "Codes : ${codes.joinToString()}\n\n" else "") +
                        "• Moteur coupé, contact mis, véhicule à l'arrêt.\n" +
                        "• Le voyant s'éteint, mais la cause n'est pas réparée : si elle persiste, le code reviendra.\n" +
                        "• L'image figée du défaut (freeze frame) et les tests de préparation sont remis à zéro " +
                        "(gênant juste avant un contrôle technique).\n" +
                        "• Les codes permanents (mode 0A) ne s'effacent pas : le calculateur les retire lui-même.",
                )
            },
            confirmButton = { Button(onClick = { confirm = false; vm.clearDtc() }) { Text("Effacer") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
        if (d == null) {
            Text("Aucune lecture pour l'instant.", color = OvermindColors.Muted)
        } else {
            Section("Résumé (${fmtTime(d.readAtMs)})") {
                KeyValue("Voyant MIL", d.milOn?.let { if (it) "ALLUMÉ" else "éteint" })
            }
            DtcList("Confirmés (mode 03)", d.stored)
            DtcList("En attente (mode 07)", d.pending)
            if (d.permanent == null) Section("Permanents (mode 0A)") { Text("Non supporté par ce calculateur.", color = OvermindColors.Muted) }
            else DtcList("Permanents (mode 0A)", d.permanent)
        }
    }
}

@Composable
private fun DtcList(title: String, list: List<Dtc>) {
    Section(title) {
        if (list.isEmpty()) Text("Aucun code.", color = OvermindColors.Ok)
        list.forEach { c ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("${c.code}${c.ecu?.let { "  ($it)" } ?: ""}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = OvermindColors.Amber)
                Text(DtcDescriptions.describe(c.code), fontSize = 14.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- 5. Historique
@Composable
fun HistoryScreen(vm: MainViewModel) {
    val sessions by vm.sessions.collectAsState()
    val up by vm.uplink.collectAsState()
    LaunchedEffect(up.pending, up.sentTotal) { vm.refreshSessions() }
    Screen {
        Section("Synchronisation") {
            KeyValue("Messages en attente", up.pending.toString())
            KeyValue("Envoyés (depuis le lancement)", up.sentTotal.toString())
            KeyValue("Dernier envoi", fmtTime(up.lastSuccessMs))
            OutlinedButton(onClick = { vm.sync() }, enabled = up.pending > 0) { Text("Synchroniser maintenant") }
        }
        if (sessions.isEmpty()) Text("Aucune session enregistrée.", color = OvermindColors.Muted)
        sessions.forEach { r ->
            Section(fmtTime(r.startedAt)) {
                KeyValue("Véhicule", r.vehicleId)
                KeyValue("Fin", r.endedAt?.let { fmtTime(it) } ?: "en cours / interrompue")
                KeyValue("Protocole", r.protocol)
                KeyValue("Messages", r.messages.toString())
                KeyValue("Défauts lus", r.dtcCount?.toString())
                KeyValue("Synchronisation", if (r.pendingSync == 0) "complète" else "${r.pendingSync} en attente")
                r.lastError?.let { Text("Dernière erreur : $it", color = OvermindColors.Muted, fontSize = 12.sp) }
            }
        }
    }
}

private val MAC_RE = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
