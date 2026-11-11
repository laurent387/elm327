package com.overmind.copilot

import android.content.Context
import com.overmind.copilot.core.ClientAction
import com.overmind.copilot.core.Protocol
import com.overmind.copilot.core.ServerMessage
import com.overmind.obdgateway.BuildConfig
import com.overmind.obdgateway.OvermindApp
import com.overmind.obdgateway.gateway.GatewayUiState
import com.overmind.obdgateway.service.GatewayService
import com.overmind.obdgateway.settings.AdapterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class KittState(val label: String) {
    STARTING("Démarrage…"), IDLE("À l'écoute"), LISTENING("Je t'écoute"), THINKING("Je réfléchis"),
    SPEAKING("Je parle"), OFFLINE("Overmind injoignable"), NOT_CONFIGURED("À configurer"), ERROR("Erreur micro"),
}

data class KittUi(
    val state: KittState = KittState.STARTING,
    val assistant: String = "Kitt",
    val transcript: String = "",
    val reply: String = "",
    val level: Double = 0.0,
    val lastLatencyMs: Int? = null,
    val error: String? = null,
)

/**
 * Chef d'orchestre du copilote, une seule instance par processus (hébergée par CopilotService).
 * Micro → (réveil) → Overmind (STT, agent, TTS) → haut-parleur + action autoradio.
 */
class CopilotRuntime private constructor(private val ctx: Context) : VoiceInput.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app = ctx.applicationContext as OvermindApp
    val settings = CopilotSettings(ctx)
    internal val client = CopilotClient(scope, BuildConfig.VERSION_NAME)
    private val voice = VoiceInput(ctx, this)
    private val speaker = Speaker(ctx) { speaking -> onSpeaking(speaking) }
    internal val actions = ClientActions(ctx)
    private val context = ContextProvider(ctx)
    private val remote = RemoteControl(ctx, this)
    private val _ui = MutableStateFlow(KittUi())
    val ui: StateFlow<KittUi> = _ui.asStateFlow()
    @Volatile private var currentId: String? = null
    private var started = false

    val gateway: StateFlow<GatewayUiState> get() = app.controller.state

    /** CA publique Overmind embarquée (assets/overmind_ca.pem) ; vide si absente du build. */
    private val bundledCa: String by lazy {
        runCatching { ctx.assets.open("overmind_ca.pem").bufferedReader().readText() }.getOrDefault("")
    }

    fun start() {
        if (started) return
        started = true
        context.start()
        scope.launch { client.events.collect { onEvent(it) } }
        scope.launch {
            client.state.collect { s ->
                _ui.update { u ->
                    u.copy(state = when {
                        s == LinkState.DISABLED -> KittState.NOT_CONFIGURED
                        s == LinkState.ONLINE && u.state in setOf(KittState.OFFLINE, KittState.NOT_CONFIGURED, KittState.STARTING) -> KittState.IDLE
                        s == LinkState.OFFLINE && u.state == KittState.IDLE -> KittState.OFFLINE
                        else -> u.state
                    }, error = if (s == LinkState.OFFLINE) client.lastError.value else u.error)
                }
            }
        }
        scope.launch {
            // Même CA privée et même véhicule que la passerelle MQTT (Réglages de la passerelle OBD).
            settings.config.combine(app.settings.settings) { cfg, gw -> Triple(cfg, gw.vehicleId, gw.caCertPem) }
                .distinctUntilChanged()
                .collect { (cfg, vehicle, ca) ->
                    voice.configure(cfg.wakeWord, cfg.wakeEnabled)
                    // CA des réglages MQTT ; à défaut, celle embarquée dans l'APK (tools/headunit_assets.sh) :
                    // un certificat PEM ne se tape pas sur l'écran de l'autoradio.
                    client.start(cfg, vehicle, ca.ifBlank { bundledCa })
                }
        }
        autoStartObd()
        voice.loadModel { voice.start() }
    }

    /**
     * ELM327 préconfiguré dans l'APK (assets/obd_adapter.txt, écrit par tools/headunit_assets.sh :
     * adresse Bluetooth de l'adaptateur de la voiture) : pris si aucun n'est choisi, puis la
     * session OBD démarre seule — rien à sélectionner sur l'autoradio. Elle se reconnecte d'elle-même.
     */
    private fun autoStartObd() {
        val mac = runCatching { ctx.assets.open("obd_adapter.txt").bufferedReader().readText().trim().uppercase() }
            .getOrDefault("").takeIf { Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(it) }
        val gw = app.settings.settings.value
        if (mac != null && gw.btAddress.isBlank() && gw.adapterTransport == AdapterTransport.BLUETOOTH) {
            app.settings.update { it.copy(btAddress = mac, btName = "OBDII (préconfiguré)") }
        }
        val s = app.settings.settings.value
        val ready = (s.adapterTransport == AdapterTransport.BLUETOOTH && s.btAddress.isNotBlank()) || s.adapterTransport == AdapterTransport.TCP
        if (ready && !gateway.value.sessionActive) {
            runCatching { GatewayService.start(ctx) }.onFailure { android.util.Log.w("Kitt", "démarrage de la session OBD impossible", it) }
        }
    }

    fun stop() {
        voice.stop(); client.stop(); context.stop(); speaker.shutdown(); started = false
    }

    /** Bouton micro de l'écran. */
    fun talk() { speaker.stop(); voice.pushToTalk() }

    /** Saisie texte (réglages / tests). */
    fun ask(text: String) {
        val id = newId()
        _ui.update { it.copy(state = KittState.THINKING, transcript = text, reply = "") }
        if (!client.sendText(Protocol.text(id, text, context.build(moving())))) offlineAnswer()
    }

    // --- Micro ----------------------------------------------------------------------------------
    override fun onWake() { speaker.earcon() }

    override fun onUtteranceStart(wake: Boolean, preRoll: ByteArray) {
        val id = newId()
        _ui.update { it.copy(state = KittState.LISTENING, transcript = "", reply = "") }
        if (client.online) {
            client.sendText(Protocol.utteranceStart(id, wake, context.build(moving())))
            if (preRoll.isNotEmpty()) client.sendPcm(preRoll)
        }
    }

    override fun onUtteranceAudio(pcm: ByteArray) { if (client.online) client.sendPcm(pcm) }

    override fun onUtteranceEnd(heardSpeech: Boolean) {
        val id = currentId ?: return
        if (!client.online) { offlineAnswer(); return }
        if (!heardSpeech) { _ui.update { it.copy(state = KittState.IDLE) }; currentId = null; return }
        client.sendText(Protocol.utteranceEnd(id))
        _ui.update { it.copy(state = KittState.THINKING) }
    }

    override fun onLevel(level: Double) { _ui.update { it.copy(level = level) } }

    override fun onError(message: String) { _ui.update { it.copy(state = KittState.ERROR, error = message) } }

    // --- Overmind -----------------------------------------------------------------------------
    private fun onEvent(e: ClientEvent) {
        when (e) {
            is ClientEvent.AudioData -> speaker.playWav(e.wav)
            is ClientEvent.UpdateChunk -> remote.onUpdateChunk(e.meta, e.data)
            is ClientEvent.PairingRejected -> {
                settings.clearPairingCode()
                _ui.update { it.copy(error = "Code d'appairage refusé ou expiré : demande un nouveau code") }
            }
            is ClientEvent.Message -> when (val m = e.msg) {
                is ServerMessage.Paired -> settings.savePairedToken(m.token)
                is ServerMessage.Welcome -> _ui.update { it.copy(assistant = m.assistant) }
                is ServerMessage.Transcript -> _ui.update { it.copy(transcript = m.text) }
                is ServerMessage.Ignored -> _ui.update { it.copy(state = KittState.IDLE, transcript = "") }
                is ServerMessage.Reply -> {
                    _ui.update { it.copy(reply = m.text) }
                    m.action?.let { a -> runAction(m.id, a) }
                }
                is ServerMessage.Done -> _ui.update {
                    it.copy(lastLatencyMs = m.totalMs, state = if (it.state == KittState.THINKING) KittState.IDLE else it.state)
                }
                is ServerMessage.UpdateData -> remote.onUpdateChunk(m, null)
                is ServerMessage.Remote -> scope.launch {
                    val (data, error) = remote.handle(m)
                    client.sendText(Protocol.remoteResult(m.id, data, error))
                }
                is ServerMessage.Error -> {
                    _ui.update { it.copy(error = m.message) }
                    offlineAnswer()
                }
                else -> Unit
            }
        }
    }

    private fun runAction(id: String, a: ClientAction) {
        scope.launch(Dispatchers.Main) {
            val (ok, detail) = actions.run(a)
            client.sendText(Protocol.actionResult(id, ok, detail))
        }
    }

    private fun onSpeaking(speaking: Boolean) {
        voice.mute(speaking)
        _ui.update { it.copy(state = if (speaking) KittState.SPEAKING else if (client.online) KittState.IDLE else KittState.OFFLINE) }
    }

    /** Overmind injoignable : réponse locale minimale, uniquement à partir de valeurs réellement lues. */
    private fun offlineAnswer() {
        val g = gateway.value
        val parts = mutableListOf("Je n'arrive pas à joindre Overmind.")
        val rpm = g.live["rpm"]?.takeIf { it.status == "ok" }?.value
        val temp = g.live["coolant_temp_c"]?.takeIf { it.status == "ok" }?.value
        if (rpm != null) parts += "Le moteur tourne à ${rpm.toInt()} tours."
        if (temp != null) parts += "Température moteur ${temp.toInt()} degrés."
        g.lastDtc?.let { d ->
            val n = d.stored.size + d.pending.size
            parts += if (n == 0) "Aucun code défaut." else "$n code${if (n > 1) "s" else ""} défaut enregistré${if (n > 1) "s" else ""}."
        }
        val text = parts.joinToString(" ")
        _ui.update { it.copy(state = KittState.OFFLINE, reply = text) }
        speaker.sayLocal(text)
    }

    private fun moving(): Boolean? = gateway.value.vehicleMoving(System.currentTimeMillis()).takeIf { it }
        ?: context.gpsSpeedKmh()?.let { it > 3 }

    private fun newId() = UUID.randomUUID().toString().take(8).also { currentId = it }

    companion object {
        @Volatile private var instance: CopilotRuntime? = null
        fun get(ctx: Context): CopilotRuntime =
            instance ?: synchronized(this) { instance ?: CopilotRuntime(ctx.applicationContext).also { instance = it } }
    }
}
