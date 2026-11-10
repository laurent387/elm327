package com.overmind.obdgateway.telemetry

import com.overmind.obdgateway.obd.dtc.Dtc
import com.overmind.obdgateway.obd.elm.DtcSnapshot
import com.overmind.obdgateway.obd.elm.ElmInfo
import com.overmind.obdgateway.obd.elm.ObdProtocol
import com.overmind.obdgateway.obd.elm.PidReading
import com.overmind.obdgateway.obd.elm.ReadStatus
import com.overmind.obdgateway.obd.pid.PidCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageFactoryTest {
    private var now = 1_794_329_100_000L // 2026-11-10T16:45:00Z
    private var seq = 0L
    private val factory = MessageFactory(
        identity = { VehicleIdentity("slk_r171", "android_01", "petrol", "supercharger", "unknown") },
        sequence = object : SequenceSource { override fun next() = ++seq },
        clock = { now },
    )

    private fun r(pid: String, v: Double?, st: ReadStatus = ReadStatus.OK, ts: Long = now) = PidReading(PidCatalog[pid]!!, v, st, "7E8", ts)
    private fun parse(m: OutgoingMessage) = Json.parseToJsonElement(m.payload).jsonObject

    @Test fun `telemetrie conforme au contrat`() {
        val m = factory.telemetry("sess-1", listOf(r("010C", 3500.0), r("010B", 98.0), r("0110", null, ReadStatus.NO_DATA)))
        val j = parse(m)
        assertEquals("overmind/vehicles/slk_r171/obd/telemetry", m.topic)
        assertEquals("1.0", j["schema_version"]!!.jsonPrimitive.content)
        assertEquals(m.messageId, j["message_id"]!!.jsonPrimitive.content)
        assertEquals(1L, j["sequence"]!!.jsonPrimitive.long)
        assertEquals("2026-11-10T16:45:00Z", j["timestamp"]!!.jsonPrimitive.content)
        val data = j["data"]!!.jsonObject
        assertEquals(3500.0, data["rpm"]!!.jsonPrimitive.double, 0.0)
        assertFalse("une valeur absente n'apparaît jamais dans data", "maf_g_s" in data)
        val maf = j["measurements"]!!.jsonArray.map { it.jsonObject }.first { it["key"]!!.jsonPrimitive.content == "maf_g_s" }
        assertEquals(JsonNull, maf["value"])
        assertEquals("no_data", maf["status"]!!.jsonPrimitive.content)
        assertEquals("obd2:7E8", maf["source"]!!.jsonPrimitive.content)
        assertFalse("pas de BARO → pas de boost", PidCatalog.BOOST_KEY in data)
        dump("telemetry", m)
    }

    @Test fun `boost relatif seulement avec BARO recent`() {
        factory.telemetry("s", listOf(r("0133", 101.0)))
        now += 30_000
        val d = parse(factory.telemetry("s", listOf(r("010B", 150.0))))["data"]!!.jsonObject
        assertEquals(49.0, d[PidCatalog.BOOST_KEY]!!.jsonPrimitive.double, 1e-9)
        now += 61_000
        val d2 = parse(factory.telemetry("s", listOf(r("010B", 150.0))))["data"]!!.jsonObject
        assertFalse("BARO périmé (> 60 s)", PidCatalog.BOOST_KEY in d2)
    }

    @Test fun `dtc, status, health, LWT`() {
        val dtc = factory.dtc("s", DtcSnapshot(true, listOf(Dtc("P0171", "7E8")), emptyList(), null, now))
        val dj = parse(dtc)["dtc"]!!.jsonObject
        assertEquals(JsonNull, dj["permanent"])
        assertEquals("P0171", dj["stored"]!!.jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content)
        val st = factory.status("s", "connected", ElmInfo("ELM327 v1.5", null, 12.6, ObdProtocol.CAN_11_500, true), "WDB1714561F123456",
            setOf("010C", "010B"), listOf("010C"), 500)
        assertTrue(st.retain)
        assertEquals("6", parse(st)["status"]!!.jsonObject["elm"]!!.jsonObject["protocol"]!!.jsonPrimitive.content)
        val h = factory.health(null, mapOf("state" to "online", "battery_pct" to 80, "bt_connected" to true))
        assertEquals("overmind/vehicles/slk_r171/gateway/health", h.topic)
        val (topic, lwt) = factory.lastWill()
        assertEquals(h.topic, topic)
        assertFalse("message_id" in Json.parseToJsonElement(lwt).jsonObject)
        assertTrue(listOf(dtc, st, h).map { it.sequence }.zipWithNext().all { (a, b) -> b > a })
        dump("dtc", dtc); dump("status", st); dump("health", h)
        File(outDir(), "lwt.json").writeText(lwt)
    }

    private fun outDir() = File("build/sample-messages").apply { mkdirs() }
    /** Échantillons validés côté Python contre schemas/overmind_obd.schema.json. */
    private fun dump(name: String, m: OutgoingMessage) = File(outDir(), "$name.json").writeText(m.payload)

    @Suppress("unused") private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
}
