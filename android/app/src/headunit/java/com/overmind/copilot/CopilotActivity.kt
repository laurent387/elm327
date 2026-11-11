package com.overmind.copilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.overmind.obdgateway.ui.LocalDisplayProfile
import com.overmind.obdgateway.ui.rememberDisplayProfile
import com.overmind.obdgateway.MainActivity
import com.overmind.obdgateway.gateway.GatewayUiState
import com.overmind.obdgateway.ui.theme.OvermindColors
import com.overmind.obdgateway.ui.theme.OvermindTheme
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Écran autoradio (paysage) : anneau vocal, ce qui a été entendu / répondu, mini-tableau OBD. */
class CopilotActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startRuntime() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Autoradio HF S8 = Android 9 (API 28) : pas de BLUETOOTH_CONNECT (31+) ni POST_NOTIFICATIONS (33+).
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.READ_CONTACTS); add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startRuntime() else perms.launch(needed.toTypedArray())
        val rt = CopilotRuntime.get(this)
        debugAsk(intent)
        setContent {
            OvermindTheme {
              val profile = rememberDisplayProfile()
              CompositionLocalProvider(LocalDisplayProfile provides profile) {
                val ui by rt.ui.collectAsState()
                val g by rt.gateway.collectAsState()
                val cfg by rt.settings.config.collectAsState()
                var showSettings by remember { mutableStateOf(!cfg.configured) }
                KittScreen(ui, g, onTalk = rt::talk, onSettings = { showSettings = true },
                    onGateway = { startActivity(Intent(this, MainActivity::class.java)) })
                if (showSettings) SettingsDialog(cfg, onSave = { c, t -> rt.settings.saveFromForm(c, t); showSettings = false },
                    onDismiss = { showSettings = false }, onTest = { rt.ask(it) })
              }
            }
        }
    }

    override fun onResume() { super.onResume(); RemoteControl.foreground = java.lang.ref.WeakReference(this) }

    override fun onPause() { RemoteControl.foreground = null; super.onPause() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); debugAsk(intent) }

    /** Builds debug uniquement : `adb shell am start -n …/.CopilotActivity --es ask "question"`. */
    private fun debugAsk(i: Intent?) {
        val q = i?.getStringExtra("ask") ?: return
        if (com.overmind.obdgateway.BuildConfig.DEBUG) CopilotRuntime.get(this).ask(q)
    }

    private fun startRuntime() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) CopilotService.start(this)
    }
}

@Composable
private fun KittScreen(ui: KittUi, g: GatewayUiState, onTalk: () -> Unit, onSettings: () -> Unit, onGateway: () -> Unit) {
    val p = LocalDisplayProfile.current
    BoxWithConstraints(Modifier.fillMaxSize().background(OvermindColors.Bg).padding(p.dp(16f))) {
        // Tailles déduites de la place réellement disponible (aucune résolution supposée).
        val ring = minOf(maxHeight * 0.62f, maxWidth * (if (p.landscape) 0.34f else 0.6f))
        val replySp = (maxHeight.value / 17f).coerceIn(18f, 34f).sp
        val left: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                VoiceRing(ui, Modifier.size(ring).clickable(onClick = onTalk))
                Text(ui.assistant.uppercase(), color = OvermindColors.Accent, fontSize = p.sp(28f, min = 18f),
                    fontWeight = FontWeight.Bold, letterSpacing = p.sp(8f, min = 4f), maxLines = 1)
                Text(ui.state.label, color = stateColor(ui.state), fontSize = p.sp(18f, min = 14f), maxLines = 1)
                ui.error?.takeIf { ui.state in setOf(KittState.OFFLINE, KittState.ERROR) }?.let {
                    Text(it, color = OvermindColors.Muted, fontSize = p.sp(12f), textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }
        val right: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (ui.transcript.isNotBlank()) Text("« ${ui.transcript} »", color = OvermindColors.Muted, fontSize = replySp * 0.65f)
                    Spacer(Modifier.height(p.dp(8f)))
                    Text(ui.reply.ifBlank { "Dis « ${ui.assistant} » ou touche l'anneau." }, color = OvermindColors.Text,
                        fontSize = replySp, lineHeight = replySp * 1.25f)
                    ui.lastLatencyMs?.let { Text("réponse en ${"%.1f".format(it / 1000.0)} s", color = OvermindColors.Muted, fontSize = p.sp(12f)) }
                }
                Spacer(Modifier.height(p.dp(8f)))
                ObdStrip(g)
                Spacer(Modifier.height(p.dp(8f)))
                // Cibles tactiles d'au moins 56 dp : utilisables du bout du doigt en voiture.
                val bh = Modifier.heightIn(min = 56.dp)
                val bt = p.sp(17f, min = 14f)
                Row(horizontalArrangement = Arrangement.spacedBy(p.dp(10f))) {
                    OutlinedButton(onClick = onGateway, modifier = bh.weight(1f)) { Text(if (p.widthDp >= 800) "Passerelle OBD" else "OBD", maxLines = 1, fontSize = bt) }
                    OutlinedButton(onClick = onSettings, modifier = bh.weight(1f)) { Text("Réglages", maxLines = 1, fontSize = bt) }
                    Button(onClick = onTalk, modifier = bh.weight(1f)) { Text("Parler", maxLines = 1, fontSize = bt) }
                }
            }
        }
        if (p.landscape) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                left(Modifier.weight(1f).fillMaxHeight())
                right(Modifier.weight(1.4f).fillMaxHeight().padding(start = p.dp(20f)))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                left(Modifier.fillMaxWidth().weight(1f))
                right(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun ObdStrip(g: GatewayUiState) {
    fun v(key: String) = g.live[key]?.takeIf { it.status == "ok" || it.status == "stale" }?.value
    val dtc = g.lastDtc?.let { it.stored.size + it.pending.size }
    val p = LocalDisplayProfile.current
    Row(Modifier.fillMaxWidth().background(OvermindColors.Surface, RoundedCornerShape(12.dp)).padding(p.dp(12f)),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("OBD", g.obdState.label.substringBefore(' '))
        Stat("Régime", v("rpm")?.let { "${it.toInt()} tr/min" } ?: "—")
        Stat("Vitesse", v("speed_kmh")?.let { "${it.toInt()} km/h" } ?: "—")
        Stat("Moteur", v("coolant_temp_c")?.let { "${it.toInt()} °C" } ?: "—")
        Stat("Défauts", dtc?.toString() ?: "—", if ((dtc ?: 0) > 0) OvermindColors.Amber else OvermindColors.Text)
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = OvermindColors.Text) {
    val p = LocalDisplayProfile.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = OvermindColors.Muted, fontSize = p.sp(12f), maxLines = 1)
        Text(value, color = color, fontSize = p.sp(22f, min = 15f), fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun stateColor(s: KittState) = when (s) {
    KittState.LISTENING -> OvermindColors.Accent
    KittState.THINKING -> OvermindColors.Amber
    KittState.SPEAKING -> OvermindColors.Ok
    KittState.OFFLINE, KittState.ERROR -> OvermindColors.Bad
    else -> OvermindColors.Muted
}

/** Anneau vocal original : ondulation radiale pilotée par le niveau micro et l'état. */
@Composable
private fun VoiceRing(ui: KittUi, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "ring").animateFloat(0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val color = stateColor(ui.state).let { if (ui.state == KittState.IDLE) OvermindColors.Accent.copy(alpha = 0.6f) else it }
    val energy = when (ui.state) {
        KittState.LISTENING -> (ui.level / 3000.0).coerceIn(0.15, 1.0).toFloat()
        KittState.SPEAKING -> 0.6f
        KittState.THINKING -> 0.35f
        else -> 0.12f
    }
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val base = min(size.width, size.height) * 0.32f
        for (ring in 0 until 3) {
            val phase = (t + ring / 3f) % 1f
            drawCircle(color.copy(alpha = (1f - phase) * 0.35f), radius = base + phase * base * 0.55f, center = c,
                style = Stroke(width = 3f))
        }
        val n = 72
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            val wobble = sin(a * 6 + t * 2 * PI).toFloat() * 0.5f + 0.5f
            val len = base * (0.08f + energy * 0.35f * wobble)
            val dx = kotlin.math.cos(a).toFloat(); val dy = sin(a).toFloat()
            drawLine(color, Offset(c.x + dx * base, c.y + dy * base), Offset(c.x + dx * (base + len), c.y + dy * (base + len)),
                strokeWidth = 5f)
        }
        drawCircle(color.copy(alpha = 0.15f), radius = base * 0.85f, center = c)
    }
}

@Composable
private fun SettingsDialog(cfg: CopilotConfig, onSave: (CopilotConfig, String) -> Unit, onDismiss: () -> Unit, onTest: (String) -> Unit) {
    var url by remember { mutableStateOf(cfg.serverUrl) }
    var token by remember { mutableStateOf("") }
    var wake by remember { mutableStateOf(cfg.wakeWord) }
    var wakeOn by remember { mutableStateOf(cfg.wakeEnabled) }
    var remoteOn by remember { mutableStateOf(cfg.remoteEnabled) }
    var question by remember { mutableStateOf("") }
    val p = LocalDisplayProfile.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Réglages Kitt") },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(if (p.compactHeight || p.widthDp < 700) 0.92f else 0.6f),
        text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text("Serveur Overmind (wss://IP:port/copilot)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, singleLine = true,
                label = { Text(when {
                    cfg.token.isNotEmpty() -> "Jeton enregistré (laisser vide) ou nouveau code"
                    cfg.pairingCode.isNotEmpty() -> "Code d'appairage en cours d'essai"
                    else -> "Code d'appairage (6 chiffres) ou jeton"
                }) },
                visualTransformation = if (token.length <= 6 && token.all { it.isDigit() }) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(wake, { wake = it }, label = { Text("Mot de réveil") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp)); Switch(wakeOn, { wakeOn = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(question, { question = it }, label = { Text("Question écrite (test)") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { if (question.isNotBlank()) onTest(question) }) { Text("Envoyer") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Télécommande Overmind")
                    Text("État, journaux, capture, musique ; mise à jour confirmée ici. Jamais le véhicule.",
                        color = OvermindColors.Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.width(12.dp)); Switch(remoteOn, { remoteOn = it })
            }
            Text("Écran détecté : ${p.summary}", color = OvermindColors.Muted, fontSize = 12.sp)
        }
    }, confirmButton = {
        Button(onClick = { onSave(cfg.copy(serverUrl = url, wakeWord = wake, wakeEnabled = wakeOn, remoteEnabled = remoteOn), token) }) {
            Text("Enregistrer")
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } })
}
