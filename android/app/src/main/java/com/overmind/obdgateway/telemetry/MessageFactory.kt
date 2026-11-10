package com.overmind.obdgateway.telemetry

import com.overmind.obdgateway.obd.elm.DtcSnapshot
import com.overmind.obdgateway.obd.elm.ElmInfo
import com.overmind.obdgateway.obd.elm.PidReading
import com.overmind.obdgateway.obd.elm.ReadStatus
import com.overmind.obdgateway.obd.dtc.Dtc
import com.overmind.obdgateway.obd.pid.PidCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.UUID
import kotlin.math.round

data class VehicleIdentity(
    val vehicleId: String,
    val gatewayId: String,
    val engineType: String,        // petrol | diesel | unknown
    val aspiration: String,        // na | turbo | supercharger | unknown
    val mapSensorLocation: String, // post_compressor | pre_throttle | intake_manifold | unknown
)

/** Construit les messages du protocole v1.0 (schemas/PROTOCOL.md). */
class MessageFactory(
    private val identity: () -> VehicleIdentity,
    private val sequence: SequenceSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    private var lastBaro: Pair<Double, Long>? = null

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun envelope(kind: String, sessionId: String?, topic: (String) -> String, retain: Boolean, body: JsonObjectBuilder.() -> Unit): OutgoingMessage {
        val id = identity()
        val now = clock()
        val seq = sequence.next()
        val msgId = idGen()
        val obj = buildJsonObject {
            put("schema_version", "1.0")
            put("kind", kind)
            put("message_id", msgId)
            put("vehicle_id", id.vehicleId)
            put("gateway_id", id.gatewayId)
            put("session_id", sessionId)
            put("timestamp", iso(now))
            put("sequence", seq)
            put("engine_type", id.engineType)
            body()
        }
        return OutgoingMessage(msgId, kind, topic(id.vehicleId), obj.toString(), 1, retain, sessionId, seq, now)
    }

    private fun round3(v: Double) = round(v * 1000.0) / 1000.0

    fun telemetry(sessionId: String, readings: List<PidReading>): OutgoingMessage {
        val ident = identity()
        val ok = readings.filter { it.status == ReadStatus.OK && it.value != null }
        val map = ok.firstOrNull { it.def.pid == "010B" }
        ok.firstOrNull { it.def.pid == "0133" }?.let { lastBaro = it.value!! to it.timestampMs }
        val baro = lastBaro?.takeIf { map != null && map.timestampMs - it.second in 0..60_000 }
        val boost = if (map != null && baro != null) round3(map.value!! - baro.first) else null

        return envelope("telemetry", sessionId, Topics::telemetry, retain = false) {
            putJsonObject("data") {
                for (r in ok) put(r.def.key, round3(r.value!!))
                if (boost != null) put(PidCatalog.BOOST_KEY, boost)
            }
            putJsonArray("measurements") {
                for (r in readings) add(buildJsonObject {
                    put("key", r.def.key)
                    put("pid", r.def.pid)
                    put("value", r.value?.takeIf { r.status == ReadStatus.OK }?.let { JsonPrimitive(round3(it)) } ?: JsonNull)
                    put("unit", r.def.unit)
                    put("ts", iso(r.timestampMs))
                    put("source", if (r.ecu != null) "obd2:${r.ecu}" else "obd2")
                    put("status", r.status.name.lowercase())
                })
                if (boost != null) add(buildJsonObject {
                    put("key", PidCatalog.BOOST_KEY)
                    put("pid", JsonNull)
                    put("value", boost)
                    put("unit", "kPa")
                    put("ts", iso(map!!.timestampMs))
                    put("source", "derived:010B+0133")
                    put("status", "ok")
                })
            }
            putJsonObject("context") {
                put("aspiration", ident.aspiration)
                put("map_sensor_location", ident.mapSensorLocation)
            }
        }
    }

    private fun dtcList(l: List<Dtc>?) = if (l == null) JsonNull else buildJsonArray {
        for (d in l) add(buildJsonObject { put("code", d.code); put("ecu", d.ecu) })
    }

    fun dtc(sessionId: String, snap: DtcSnapshot): OutgoingMessage =
        envelope("dtc", sessionId, Topics::dtc, retain = false) {
            put("dtc", buildJsonObject {
                put("mil_on", snap.milOn)
                put("stored", dtcList(snap.stored))
                put("pending", dtcList(snap.pending))
                put("permanent", dtcList(snap.permanent))
                put("read_at", iso(snap.readAtMs))
            })
        }

    fun status(
        sessionId: String?, state: String, elm: ElmInfo?, vin: String?,
        supported: Collection<String>, polled: Collection<String>, pollIntervalMs: Long, detail: String? = null,
    ): OutgoingMessage = envelope("status", sessionId, Topics::status, retain = true) {
        putJsonObject("status") {
            put("state", state)
            if (detail != null) put("detail", detail)
            if (elm != null) putJsonObject("elm") {
                put("version", elm.version)
                put("protocol", elm.protocol?.code?.toString())
                put("protocol_name", elm.protocol?.label)
                put("voltage_v", elm.voltage)
                put("headers", elm.headers)
            }
            put("vin", vin)
            put("supported_pids", JsonArray(supported.sorted().map { JsonPrimitive(it) }))
            put("polled_pids", JsonArray(polled.map { JsonPrimitive(it) }))
            put("poll_interval_ms", pollIntervalMs)
        }
    }

    fun health(sessionId: String?, fields: Map<String, Any?>): OutgoingMessage =
        envelope("health", sessionId, Topics::health, retain = true) {
            putJsonObject("health") {
                for ((k, v) in fields) when (v) {
                    null -> put(k, JsonNull)
                    is Boolean -> put(k, v)
                    is Number -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }

    /** Dernières volontés MQTT (publiées par le broker si la passerelle disparaît). */
    fun lastWill(): Pair<String, String> {
        val id = identity()
        val obj = buildJsonObject {
            put("schema_version", "1.0")
            put("kind", "health")
            put("vehicle_id", id.vehicleId)
            put("gateway_id", id.gatewayId)
            putJsonObject("health") { put("state", "offline") }
        }
        return Topics.health(id.vehicleId) to obj.toString()
    }
}
