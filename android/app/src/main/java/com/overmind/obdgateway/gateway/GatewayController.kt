package com.overmind.obdgateway.gateway

import com.overmind.obdgateway.buffer.Outbox
import com.overmind.obdgateway.buffer.SessionRecord
import com.overmind.obdgateway.buffer.SessionStore
import com.overmind.obdgateway.net.OutboxDrainer
import com.overmind.obdgateway.obd.elm.DtcSnapshot
import com.overmind.obdgateway.obd.elm.Elm327Session
import com.overmind.obdgateway.obd.elm.ElmConnection
import com.overmind.obdgateway.obd.elm.ElmInfo
import com.overmind.obdgateway.obd.elm.NoVehicleException
import com.overmind.obdgateway.obd.elm.PidReading
import com.overmind.obdgateway.obd.elm.ReadStatus
import com.overmind.obdgateway.obd.pid.PidCatalog
import com.overmind.obdgateway.obd.pid.PidDef
import com.overmind.obdgateway.obd.transport.ObdTransport
import com.overmind.obdgateway.settings.GatewaySettings
import com.overmind.obdgateway.telemetry.MessageFactory
import com.overmind.obdgateway.telemetry.OutgoingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

enum class ObdState(val label: String) {
    IDLE("Inactif"),
    CONNECTING("Connexion à l'adaptateur…"),
    INITIALIZING("Initialisation ELM327…"),
    SEARCHING_PROTOCOL("Recherche du protocole OBD…"),
    RUNNING("Acquisition en cours"),
    NO_VEHICLE("Aucun calculateur (contact ?)"),
    RECONNECTING("Reconnexion…"),
    STOPPED("Arrêté"),
}

data class LiveValue(
    val pid: String?, val key: String, val label: String, val unit: String,
    val value: Double?, val status: String, val ecu: String?, val timestampMs: Long,
)

data class GatewayUiState(
    val obdState: ObdState = ObdState.IDLE,
    val sessionId: String? = null,
    val adapter: String? = null,
    val elm: ElmInfo? = null,
    val vin: String? = null,
    val supportedPids: Set<String> = emptySet(),
    val polledPids: List<String> = emptyList(),
    val live: Map<String, LiveValue> = emptyMap(),
    val lastDtc: DtcSnapshot? = null,
    val dtcReading: Boolean = false,
    val lastError: String? = null,
    val cycleMs: Long? = null,
    val log: List<String> = emptyList(),
) {
    val sessionActive: Boolean get() = obdState != ObdState.IDLE && obdState != ObdState.STOPPED

    /**
     * Véhicule en mouvement : dernière vitesse OBD valide > 0, lue pendant une session active
     * et il y a moins de [maxAgeMs]. Une vitesse figée d'une session arrêtée ne verrouille rien.
     */
    fun vehicleMoving(nowMs: Long, maxAgeMs: Long = MOVING_MAX_AGE_MS): Boolean {
        if (!sessionActive) return false
        val v = live["speed_kmh"] ?: return false
        return v.status == "ok" && (v.value ?: 0.0) > 0.0 && nowMs - v.timestampMs in 0..maxAgeMs
    }

    companion object { const val MOVING_MAX_AGE_MS = 10_000L }
}

/**
 * Cœur de la passerelle : session ELM327 → acquisition → messages → outbox.
 * Une session (session_id) dure du démarrage à l'arrêt par l'utilisateur et survit aux
 * reconnexions Bluetooth. Lecture seule côté véhicule.
 */
class GatewayController(
    private val outbox: Outbox,
    private val sessions: SessionStore,
    private val drainer: OutboxDrainer,
    private val messageFactory: MessageFactory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(GatewayUiState())
    val state: StateFlow<GatewayUiState> = _state.asStateFlow()
    private val dtcRequested = AtomicBoolean(false)
    private var pendingMsgCount = 0

    fun requestDtcRead() { dtcRequested.set(true) }

    fun log(msg: String) {
        val line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + msg
        _state.update { it.copy(log = (it.log + line).takeLast(200)) }
    }

    private fun emit(m: OutgoingMessage) {
        outbox.enqueue(m)
        drainer.wake()
        pendingMsgCount++
        if (pendingMsgCount >= 20) flushCounts()
    }

    private fun flushCounts() {
        val sid = _state.value.sessionId ?: return
        if (pendingMsgCount > 0) sessions.update(sid, addMessages = pendingMsgCount)
        pendingMsgCount = 0
    }

    fun publishHealth(fields: Map<String, Any?>) {
        emit(messageFactory.health(_state.value.sessionId, mapOf("state" to "online") + fields))
    }

    private fun publishStatus(state: String, s: GatewaySettings, detail: String? = null) {
        val st = _state.value
        emit(messageFactory.status(st.sessionId, state, st.elm, st.vin, st.supportedPids, st.polledPids, s.pollIntervalMs, detail))
    }

    /**
     * Boucle de session complète ; rend la main à l'annulation de la coroutine.
     * [transportFactory] crée un nouveau transport à chaque (re)connexion.
     */
    suspend fun runSession(settings: GatewaySettings, transportFactory: () -> ObdTransport) = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        _state.value = GatewayUiState(sessionId = sessionId, obdState = ObdState.CONNECTING, log = _state.value.log)
        sessions.start(SessionRecord(sessionId, settings.vehicleId, clock(), null, null, null, null, 0, null, null))
        log("Session $sessionId démarrée (${settings.vehicleId})")
        var backoff = 2_000L
        try {
            while (coroutineContext.isActive) {
                val transport = transportFactory()
                _state.update { it.copy(adapter = transport.description, obdState = ObdState.CONNECTING) }
                try {
                    transport.open()
                    log("Adaptateur connecté : ${transport.description}")
                    val elm = Elm327Session(ElmConnection(transport), clock) { log(it) }
                    elm.multiPidEnabled = settings.multiPid
                    _state.update { it.copy(obdState = ObdState.INITIALIZING) }
                    // Ne jamais appeler de code qui modifie _state DANS update {} (CAS rejoué sans fin).
                    val info = elm.initialize()
                    _state.update { it.copy(elm = info) }
                    _state.update { it.copy(obdState = ObdState.SEARCHING_PROTOCOL) }
                    elm.detectProtocol()
                    val supported = elm.discoverSupportedPids()
                    val vin = elm.readVin()
                    _state.update { it.copy(elm = elm.info, supportedPids = supported, vin = vin, lastError = null) }
                    sessions.update(sessionId, protocol = elm.info.protocol?.label, vin = vin)
                    backoff = 2_000L
                    acquisitionLoop(elm, settings, sessionId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NoVehicleException) {
                    _state.update { it.copy(obdState = ObdState.NO_VEHICLE, lastError = e.message) }
                    log(e.message ?: "Aucun calculateur")
                    publishStatus("no_vehicle", settings, e.message)
                    sleep(10_000)
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    _state.update { it.copy(obdState = ObdState.RECONNECTING, lastError = msg) }
                    sessions.update(sessionId, lastError = msg)
                    log("Liaison perdue : $msg — nouvel essai dans ${backoff / 1000} s")
                    publishStatus("disconnected", settings, msg)
                    sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                } finally {
                    runCatching { transport.close() }
                }
            }
        } finally {
            withContext(NonCancellable) {
                _state.update { it.copy(obdState = ObdState.STOPPED) }
                publishStatus("disconnected", settings, "session arrêtée")
                flushCounts()
                sessions.end(sessionId, clock())
                log("Session arrêtée")
            }
        }
    }

    private suspend fun acquisitionLoop(elm: Elm327Session, s: GatewaySettings, sessionId: String) {
        val supported = elm.supportedPids
        val catalog = PidCatalog.all.filter { it.pid in supported }
        val fast = catalog.filter { it.fast || it.pid in PidCatalog.MVP }
        val slow = catalog - fast.toSet()
        val polled = (fast + slow).map { it.pid }
        _state.update { st ->
            st.copy(
                obdState = ObdState.RUNNING, polledPids = polled,
                live = PidCatalog.all.associate { d ->
                    d.key to LiveValue(d.pid, d.key, d.label, d.unit, null, if (d.pid in supported) "pending" else "unsupported", null, clock())
                },
            )
        }
        publishStatus("connected", s)
        log("Acquisition : ${fast.size} PID rapides, ${slow.size} PID lents, période ${s.pollIntervalMs} ms")

        var slowIdx = 0
        var lastDtc = 0L
        var lastVolt = clock()
        var failedCycles = 0
        while (coroutineContext.isActive) {
            val t0 = clock()
            val batch = ArrayList<PidDef>(fast)
            if (slow.isNotEmpty()) batch += slow[slowIdx++ % slow.size]
            val readings = elm.readPids(batch)
            val ok = readings.count { it.status == ReadStatus.OK }
            failedCycles = if (ok == 0 && readings.isNotEmpty()) failedCycles + 1 else 0
            if (failedCycles >= 10) throw IOException("10 cycles sans réponse du calculateur")
            if (readings.isNotEmpty()) {
                val msg = messageFactory.telemetry(sessionId, readings)
                emit(msg)
                updateLive(readings, msg)
            }
            if (dtcRequested.getAndSet(false) || t0 - lastDtc >= s.dtcIntervalS * 1000 || lastDtc == 0L) {
                readDtc(elm, sessionId)
                lastDtc = clock()
            }
            if (t0 - lastVolt > 30_000) {
                elm.readVoltage()?.let { v -> _state.update { it.copy(elm = it.elm?.copy(voltage = v)) } }
                lastVolt = clock()
            }
            val elapsed = clock() - t0
            _state.update { it.copy(cycleMs = elapsed) }
            if (elapsed < s.pollIntervalMs) sleep(s.pollIntervalMs - elapsed)
        }
    }

    private fun readDtc(elm: Elm327Session, sessionId: String) {
        _state.update { it.copy(dtcReading = true) }
        try {
            val snap = elm.readDtcs()
            emit(messageFactory.dtc(sessionId, snap))
            sessions.update(sessionId, dtcCount = snap.stored.size + snap.pending.size + (snap.permanent?.size ?: 0))
            _state.update { it.copy(lastDtc = snap) }
            log("DTC : ${snap.stored.size} confirmé(s), ${snap.pending.size} en attente, " +
                (snap.permanent?.let { "${it.size} permanent(s)" } ?: "permanents non supportés"))
        } finally {
            _state.update { it.copy(dtcReading = false) }
        }
    }

    private fun updateLive(readings: List<PidReading>, msg: OutgoingMessage) {
        _state.update { st ->
            val live = st.live.toMutableMap()
            for (r in readings) {
                val prev = live[r.def.key]
                // Une lecture en échec n'efface pas l'affichage d'une valeur récente : elle passe en "stale".
                live[r.def.key] = if (r.status == ReadStatus.OK) {
                    LiveValue(r.def.pid, r.def.key, r.def.label, r.def.unit, r.value, "ok", r.ecu, r.timestampMs)
                } else {
                    LiveValue(r.def.pid, r.def.key, r.def.label, r.def.unit, null, r.status.name.lowercase(), r.ecu, r.timestampMs)
                        .let { if (prev?.value != null) prev.copy(status = "stale") else it }
                }
            }
            boostFrom(msg)?.let { b ->
                live[PidCatalog.BOOST_KEY] = LiveValue(null, PidCatalog.BOOST_KEY, "Suralimentation relative (MAP − BARO)", "kPa", b, "ok", null, clock())
            }
            st.copy(live = live)
        }
    }

    private fun boostFrom(msg: OutgoingMessage): Double? =
        Regex("\"${PidCatalog.BOOST_KEY}\":(-?[0-9.]+)").find(msg.payload)?.groupValues?.get(1)?.toDoubleOrNull()
}
