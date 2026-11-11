package com.overmind.copilot

import com.overmind.copilot.core.Protocol
import com.overmind.copilot.core.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

enum class LinkState { DISABLED, CONNECTING, ONLINE, OFFLINE }

sealed class ClientEvent {
    data class Message(val msg: ServerMessage) : ClientEvent()
    data class AudioData(val id: String, val wav: ByteArray) : ClientEvent()
    /** Code d'appairage refusé : ne pas le réessayer en boucle (il serait annulé après 5 essais). */
    data class PairingRejected(val reason: String) : ClientEvent()
    data class UpdateChunk(val meta: ServerMessage.UpdateData, val data: ByteArray) : ClientEvent()
}

/** WebSocket vers overmind_copilot, reconnexion automatique (connexion sortante uniquement). */
class CopilotClient(private val scope: CoroutineScope, private val appVersion: String) {
    private val baseHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val state = MutableStateFlow(LinkState.DISABLED)
    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ClientEvent> = _events
    val lastError = MutableStateFlow<String?>(null)

    @Volatile private var ws: WebSocket? = null

    @Volatile private var pendingAudioId: String? = null
    @Volatile private var pendingUpdate: ServerMessage.UpdateData? = null
    private var loop: Job? = null

    /**
     * [caPem] : CA privée Overmind (la même que pour MQTT, Réglages de la passerelle). Vide =
     * magasin système. Le nom d'hôte reste vérifié (le certificat porte l'IP publique en SAN).
     */
    fun start(cfg: CopilotConfig, vehicleId: String, caPem: String = "") {
        stop()
        if (!cfg.configured || (cfg.token.isEmpty() && cfg.pairingCode.isEmpty())) { state.value = LinkState.DISABLED; return }
        val http = if (cfg.serverUrl.startsWith("wss://") && caPem.isNotBlank()) {
            val tm = pinnedTrustManager(caPem)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            baseHttp.newBuilder().sslSocketFactory(ctx.socketFactory, tm).build()
        } else baseHttp
        loop = scope.launch {
            var backoff = 2_000L
            while (isActive) {
                state.value = LinkState.CONNECTING
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                val req = Request.Builder().url(cfg.serverUrl).build()
                ws = http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(Protocol.hello(cfg.token, cfg.deviceId, vehicleId, appVersion, cfg.remoteEnabled, cfg.pairingCode))
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val m = Protocol.parse(text)
                        if (m is ServerMessage.Welcome) { state.value = LinkState.ONLINE; lastError.value = null; backoff = 2_000L }
                        if (m is ServerMessage.Audio) { pendingAudioId = m.id; pendingUpdate = null }
                        if (m is ServerMessage.UpdateData && m.error == null) { pendingUpdate = m; return }
                        _events.tryEmit(ClientEvent.Message(m))
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        pendingUpdate?.let { u ->
                            pendingUpdate = null
                            _events.tryEmit(ClientEvent.UpdateChunk(u, bytes.toByteArray()))
                            return
                        }
                        _events.tryEmit(ClientEvent.AudioData(pendingAudioId ?: "", bytes.toByteArray()))
                        pendingAudioId = null
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        lastError.value = "fermé par Overmind ($code ${reason.take(60)})"
                        if (reason.contains("appairage")) _events.tryEmit(ClientEvent.PairingRejected(reason))
                        webSocket.close(1000, null)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        lastError.value = t.message ?: t.javaClass.simpleName
                        closed.complete(Unit)
                    }
                })
                closed.await()
                ws = null
                state.value = LinkState.OFFLINE
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        ws?.close(1000, "arrêt"); ws = null
        state.value = LinkState.DISABLED
    }

    val online get() = state.value == LinkState.ONLINE && ws != null

    companion object {
        fun pinnedTrustManager(caPem: String): X509TrustManager {
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            cf.generateCertificates(ByteArrayInputStream(caPem.toByteArray())).forEachIndexed { i, c ->
                ks.setCertificateEntry("overmind_ca_$i", c)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }

    fun sendText(json: String): Boolean = ws?.send(json) ?: false
    fun sendPcm(bytes: ByteArray): Boolean = ws?.send(bytes.toByteString()) ?: false
}
