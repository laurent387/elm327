package com.overmind.obdgateway.net

import com.overmind.obdgateway.buffer.Outbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class UplinkState(
    val connected: Boolean = false,
    val pending: Int = 0,
    val sentTotal: Long = 0,
    val lastError: String? = null,
    val lastSuccessMs: Long? = null,
)

/**
 * Vide l'outbox vers le broker : plus anciens d'abord, par lots, suppression uniquement après
 * PUBACK. Reconnexion avec backoff exponentiel (1 s → 60 s), réveillée immédiatement par
 * [wake] (nouveau message, retour du réseau).
 */
class OutboxDrainer(
    private val outbox: Outbox,
    private val linkFactory: () -> MqttLink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = 32,
    private val maxRows: Int = 200_000,
    private val log: (String) -> Unit = {},
) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(UplinkState())
    val state: StateFlow<UplinkState> = _state.asStateFlow()
    private var link: MqttLink? = null

    fun wake() { signal.trySend(Unit) }

    /** Force une reconnexion (paramètres modifiés). */
    fun reset() { link?.disconnect(); link = null; wake() }

    suspend fun run() {
        var backoffMs = 1_000L
        try {
            while (coroutineContext.isActive) {
                val dropped = outbox.trim(maxRows)
                if (dropped > 0) log("Outbox pleine : $dropped mesures anciennes abandonnées")
                val pending = outbox.count()
                _state.value = _state.value.copy(pending = pending, connected = link?.isConnected == true)
                if (pending == 0) {
                    withTimeoutOrNull(5_000) { signal.receive() }
                    continue
                }
                val l = link ?: linkFactory().also { link = it }
                if (!l.isConnected) {
                    try {
                        l.connect()
                        log("MQTT connecté")
                        backoffMs = 1_000L
                        _state.value = _state.value.copy(connected = true, lastError = null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(connected = false, lastError = e.message ?: e.javaClass.simpleName)
                        log("MQTT indisponible (${e.message}), nouvel essai dans ${backoffMs / 1000} s")
                        withTimeoutOrNull(backoffMs) { signal.receive() }
                        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                        continue
                    }
                }
                val batch = outbox.peek(batchSize)
                val acked = try { l.publish(batch) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptySet() }
                outbox.remove(acked)
                if (acked.size < batch.size) {
                    // Échec partiel : on garde le reste, on force une reconnexion.
                    l.disconnect()
                    _state.value = _state.value.copy(connected = false, lastError = "publication interrompue")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                } else {
                    _state.value = _state.value.copy(sentTotal = _state.value.sentTotal + acked.size, lastSuccessMs = clock())
                }
            }
        } finally {
            link?.disconnect()
            link = null
            _state.value = _state.value.copy(connected = false)
        }
    }
}
