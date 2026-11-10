package com.overmind.obdgateway.obd.elm

import com.overmind.obdgateway.obd.dtc.Dtc
import com.overmind.obdgateway.obd.dtc.DtcDecoder
import com.overmind.obdgateway.obd.pid.PidCatalog
import com.overmind.obdgateway.obd.pid.PidDef
import java.io.IOException

data class ElmInfo(
    val version: String?,        // "ELM327 v1.5"
    val deviceDescription: String?,
    val voltage: Double?,
    val protocol: ObdProtocol?,
    val headers: Boolean,
)

enum class ReadStatus { OK, NO_DATA, ERROR, UNSUPPORTED }

data class PidReading(
    val def: PidDef,
    val value: Double?,
    val status: ReadStatus,
    val ecu: String?,
    val timestampMs: Long,
)

data class DtcSnapshot(
    val milOn: Boolean?,
    val stored: List<Dtc>,
    val pending: List<Dtc>,
    val permanent: List<Dtc>?, // null = service 0A non supporté
    val readAtMs: Long,
)

class NoVehicleException(msg: String) : IOException(msg)

/**
 * Session de haut niveau avec un ELM327 : initialisation, identification, détection du
 * protocole, découverte des PID supportés, lecture des PID, DTC et VIN.
 * Toutes les méthodes sont bloquantes (Dispatchers.IO) et en LECTURE SEULE.
 */
class Elm327Session(
    private val conn: ElmConnection,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    var info: ElmInfo = ElmInfo(null, null, null, null, headers = false)
        private set
    var supportedPids: Set<String> = emptySet()
        private set
    /** Requêtes multi-PID (CAN uniquement) ; désactivées automatiquement au premier échec. */
    var multiPidEnabled = true

    private val protocol: ObdProtocol get() = info.protocol ?: ObdProtocol.CAN_11_500

    /** Réinitialise l'ELM et configure un mode de dialogue stable. */
    fun initialize(): ElmInfo {
        val reset = conn.send("ATZ", ElmConnection.RESET_TIMEOUT_MS)
        val version = reset.lines.lastOrNull { it.uppercase().contains("ELM") }
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATM0", "ATAT1")) {
            val r = conn.send(cmd)
            if (r.error == ElmError.UNKNOWN_COMMAND) log("$cmd non supporté par l'adaptateur (ignoré)")
        }
        val headers = conn.send("ATH1").lines.any { it.uppercase().contains("OK") }
        conn.send("ATSP0")
        val desc = runCatching { conn.send("AT@1").lines.firstOrNull() }.getOrNull()
        val volt = readVoltage()
        info = ElmInfo(version, desc?.takeUnless { it == "?" }, volt, null, headers)
        log("ELM initialisé : ${version ?: "version inconnue"}, en-têtes=${headers}, ${volt ?: "?"} V")
        return info
    }

    fun readVoltage(): Double? = runCatching {
        conn.send("ATRV").lines.firstOrNull()?.uppercase()?.removeSuffix("V")?.trim()?.toDouble()
    }.getOrNull()

    /**
     * Recherche du protocole : 0100 force la recherche automatique, puis ATDPN donne le
     * protocole retenu. Lance [NoVehicleException] si aucun calculateur ne répond.
     */
    fun detectProtocol(): ObdProtocol {
        val r = conn.send("0100", ElmConnection.SEARCH_TIMEOUT_MS)
        when (r.error) {
            null -> {}
            ElmError.UNABLE_TO_CONNECT, ElmError.NO_DATA, ElmError.BUS_ERROR, ElmError.CAN_ERROR ->
                throw NoVehicleException("Aucun calculateur ne répond (${r.error.token}). Contact mis ?")
            else -> throw IOException("Recherche protocole : ${r.error.token}")
        }
        val dpn = conn.send("ATDPN").lines.firstOrNull() ?: ""
        val p = ObdProtocol.fromDpn(dpn) ?: throw IOException("Protocole inconnu : $dpn")
        info = info.copy(protocol = p)
        log("Protocole détecté : ${p.label}")
        // Réutilise la réponse 0100 pour le premier bloc de support.
        return p
    }

    /** Découvre les PID mode 01 supportés via 0100, 0120, 0140… (union de tous les calculateurs). */
    fun discoverSupportedPids(): Set<String> {
        val supported = sortedSetOf<String>()
        var base = 0x00
        while (base <= 0xE0) {
            val cmd = "01%02X".format(base)
            val r = conn.send(cmd, if (base == 0) ElmConnection.SEARCH_TIMEOUT_MS else ElmConnection.DEFAULT_TIMEOUT_MS)
            if (!r.isOk) break
            var nextBlock = false
            for (p in payloads(r)) {
                val b = p.bytes
                if (b.size < 6 || b[0] != 0x41 || b[1] != base) continue
                for (i in 0 until 32) {
                    val byte = b[2 + i / 8]
                    if (byte and (0x80 shr (i % 8)) != 0) {
                        val pid = base + i + 1
                        if (pid % 0x20 == 0) nextBlock = true else supported += "01%02X".format(pid)
                    }
                }
            }
            if (!nextBlock) break
            base += 0x20
        }
        supportedPids = supported
        log("PID supportés : ${supported.size}")
        return supported
    }

    fun payloads(r: ElmResponse): List<EcuPayload> = ObdResponseParser.parse(r.lines, protocol, info.headers)

    /** Lit un PID. Ne renvoie jamais de valeur si la trame ne correspond pas exactement. */
    fun readPid(def: PidDef): PidReading {
        val now = clock()
        if (supportedPids.isNotEmpty() && def.pid !in supportedPids) return PidReading(def, null, ReadStatus.UNSUPPORTED, null, now)
        val r = conn.send(def.pid)
        if (r.error == ElmError.NO_DATA) return PidReading(def, null, ReadStatus.NO_DATA, null, clock())
        if (r.error != null) return PidReading(def, null, ReadStatus.ERROR, null, clock())
        return extract(def, payloads(r), clock())
            ?: PidReading(def, null, ReadStatus.ERROR, null, clock())
    }

    private fun extract(def: PidDef, payloads: List<EcuPayload>, ts: Long): PidReading? {
        // Priorité au calculateur moteur (7E8 / 10) s'il répond.
        val ordered = payloads.sortedBy { if (it.ecu == "7E8" || it.ecu == "10") 0 else 1 }
        for (p in ordered) {
            val b = p.bytes
            if (b.size >= 3 && b[0] == 0x41 && b[1] == def.pidByte) {
                val data = b.copyOfRange(2, b.size)
                val v = def.decode(data) ?: continue
                if (v.isNaN() || v.isInfinite()) continue
                return PidReading(def, v, ReadStatus.OK, p.ecu, ts)
            }
        }
        return null
    }

    /**
     * Lit plusieurs PID. Sur CAN, regroupe jusqu'à 6 PID par requête (moins d'aller-retours,
     * moins de batterie). Si la réponse groupée est incohérente, repasse en mode unitaire.
     */
    fun readPids(defs: List<PidDef>): List<PidReading> {
        val todo = defs.filter { supportedPids.isEmpty() || it.pid in supportedPids }
        val unsupported = (defs - todo.toSet()).map { PidReading(it, null, ReadStatus.UNSUPPORTED, null, clock()) }
        // Les PID à structure complexe (≥ 0x60, longueurs variables selon calculateur) restent unitaires.
        val (groupable, single) = todo.partition { it.pidByte < 0x60 }
        if (!(multiPidEnabled && protocol.isCan) || groupable.size < 2) return unsupported + todo.map { readPid(it) }
        val out = ArrayList<PidReading>(defs.size)
        out += unsupported
        out += single.map { readPid(it) }
        for (chunk in groupable.chunked(6)) {
            var grouped = runCatching { readGrouped(chunk) }.getOrNull()
            if (grouped != null && grouped.none { it.status == ReadStatus.OK }) {
                // Tout en NO DATA : calculateur qui ne gère pas les requêtes groupées ? Vérification unitaire.
                val singles = chunk.map { readPid(it) }
                if (singles.any { it.status == ReadStatus.OK }) {
                    multiPidEnabled = false
                    log("Requêtes multi-PID non gérées par le calculateur : passage en unitaire")
                }
                out += singles
                continue
            }
            if (grouped == null) {
                multiPidEnabled = false
                log("Requêtes multi-PID désactivées (réponse non exploitable)")
                out += chunk.map { readPid(it) }
            } else out += grouped
        }
        return out
    }

    private fun readGrouped(chunk: List<PidDef>): List<PidReading>? {
        val cmd = "01" + chunk.joinToString("") { it.pid.substring(2) }
        val r = conn.send(cmd)
        val ts = clock()
        if (r.error == ElmError.NO_DATA) return chunk.map { PidReading(it, null, ReadStatus.NO_DATA, null, ts) }
        if (r.error != null) return null
        val found = HashMap<String, PidReading>()
        val ordered = payloads(r).sortedBy { if (it.ecu == "7E8" || it.ecu == "10") 0 else 1 }
        for (p in ordered) {
            val b = p.bytes
            if (b.isEmpty() || b[0] != 0x41) continue
            var i = 1
            while (i < b.size) {
                val def = PidCatalog.byPidByte(b[i]) ?: return null
                val end = i + 1 + def.dataBytes
                if (end > b.size) return null
                if (def in chunk && def.pid !in found) {
                    val v = def.decode(b.copyOfRange(i + 1, end))
                    found[def.pid] = if (v != null) PidReading(def, v, ReadStatus.OK, p.ecu, ts)
                                     else PidReading(def, null, ReadStatus.ERROR, p.ecu, ts)
                }
                i = end
            }
        }
        return chunk.map { found[it.pid] ?: PidReading(it, null, ReadStatus.NO_DATA, null, ts) }
    }

    /** Lecture des défauts : 0101 (MIL), 03 confirmés, 07 en attente, 0A permanents. */
    fun readDtcs(): DtcSnapshot {
        val isCan = protocol.isCan
        val mil = runCatching {
            val r = conn.send("0101")
            payloads(r).firstOrNull { it.bytes.size >= 3 && it.bytes[0] == 0x41 && it.bytes[1] == 0x01 }
                ?.let { (it.bytes[2] and 0x80) != 0 }
        }.getOrNull()
        fun read(mode: String, sid: Int, nullIfUnsupported: Boolean): List<Dtc>? {
            val r = conn.send(mode, 5_000)
            return when (r.error) {
                null -> DtcDecoder.parse(payloads(r), sid, isCan)
                ElmError.NO_DATA -> if (nullIfUnsupported) null else emptyList()
                ElmError.UNKNOWN_COMMAND -> null
                else -> throw IOException("Lecture $mode : ${r.error.token}")
            }
        }
        val stored = read("03", 0x43, false) ?: emptyList()
        val pending = read("07", 0x47, false) ?: emptyList()
        val permanent = read("0A", 0x4A, true)
        return DtcSnapshot(mil, stored, pending, permanent, clock())
    }

    /** VIN (service 09 PID 02). Renvoie null si non disponible. */
    fun readVin(): String? = runCatching {
        val r = conn.send("0902", 5_000)
        if (!r.isOk) return null
        val ps = payloads(r).filter { it.bytes.size >= 3 && it.bytes[0] == 0x49 && it.bytes[1] == 0x02 }
        val bytes = if (protocol.isCan) {
            ps.firstOrNull()?.bytes?.let { it.copyOfRange(3, it.size) }
        } else {
            ps.sortedBy { it.bytes[2] }.flatMap { it.bytes.copyOfRange(3, it.bytes.size).toList() }.toIntArray()
        } ?: return null
        val vin = bytes.filter { it in 0x30..0x5A }.map { it.toChar() }.joinToString("")
        vin.takeLast(17).takeIf { it.length == 17 }
    }.getOrNull()
}
