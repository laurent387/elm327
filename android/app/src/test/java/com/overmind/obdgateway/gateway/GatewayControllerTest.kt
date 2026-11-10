package com.overmind.obdgateway.gateway

import com.overmind.obdgateway.buffer.InMemoryOutbox
import com.overmind.obdgateway.buffer.SessionRecord
import com.overmind.obdgateway.buffer.SessionStore
import com.overmind.obdgateway.net.OutboxDrainer
import com.overmind.obdgateway.net.FakeLink
import com.overmind.obdgateway.settings.GatewaySettings
import com.overmind.obdgateway.sim.FakeElm327
import com.overmind.obdgateway.telemetry.MessageFactory
import com.overmind.obdgateway.telemetry.SequenceSource
import com.overmind.obdgateway.telemetry.VehicleIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemSessions : SessionStore {
    val records = HashMap<String, SessionRecord>()
    override fun start(r: SessionRecord) { records[r.sessionId] = r }
    override fun update(sessionId: String, protocol: String?, vin: String?, addMessages: Int, dtcCount: Int?, lastError: String?) {
        val r = records[sessionId]!!
        records[sessionId] = r.copy(protocol = protocol ?: r.protocol, vin = vin ?: r.vin, messages = r.messages + addMessages,
            dtcCount = dtcCount ?: r.dtcCount, lastError = lastError ?: r.lastError)
    }
    override fun end(sessionId: String, at: Long) { records[sessionId] = records[sessionId]!!.copy(endedAt = at) }
    override fun recent(limit: Int) = records.values.toList()
}

class GatewayControllerTest {

    private fun setup(): Triple<GatewayController, InMemoryOutbox, MemSessions> {
        val outbox = InMemoryOutbox()
        val sessions = MemSessions()
        var seq = 0L
        val factory = MessageFactory({ VehicleIdentity("slk_r171", "android_01", "petrol", "supercharger", "unknown") },
            object : SequenceSource { override fun next() = ++seq })
        val drainer = OutboxDrainer(outbox, { FakeLink() })
        return Triple(GatewayController(outbox, sessions, drainer, factory, sleep = { delay(minOf(it, 20)) }), outbox, sessions)
    }

    private fun kinds(o: InMemoryOutbox) = o.peek(10_000).map { it.kind }

    @Test fun `pipeline MVP - ELM vers outbox`() = runBlocking {
        val (c, outbox, sessions) = setup()
        val sim = FakeElm327(stored = listOf(0x0171))
        val job = launch { c.runSession(GatewaySettings(pollIntervalMs = 100)) { sim.transport() } }
        withTimeout(10_000) { while (kinds(outbox).count { it == "telemetry" } < 5) delay(20) }
        job.cancel(); job.join()
        val st = c.state.value
        assertEquals(ObdState.STOPPED, st.obdState)
        assertEquals(3500.0, st.live["rpm"]!!.value!!, 1e-9)
        assertEquals("P0171", st.lastDtc!!.stored.single().code)
        val k = kinds(outbox)
        assertEquals("status", k.first())
        assertTrue("dtc" in k)
        assertEquals("status", k.last()) // statut "disconnected" à l'arrêt
        val t = outbox.peek(10_000).first { it.kind == "telemetry" }
        val data = Json.parseToJsonElement(t.payload).jsonObject["data"]!!.jsonObject
        assertTrue(data.keys.containsAll(listOf("rpm", "map_kpa_absolute", "engine_load_pct")))
        val rec = sessions.records.values.single()
        assertTrue(rec.endedAt != null)
        assertTrue(rec.messages > 0)
        assertEquals("WDB1714561F123456", rec.vin)
    }

    @Test fun `reconnexion apres perte Bluetooth, meme session`() = runBlocking {
        val (c, outbox, sessions) = setup()
        var opens = 0
        val sim = FakeElm327()
        val job = launch {
            c.runSession(GatewaySettings(pollIntervalMs = 50)) { opens++; sim.transport(dropAfterCommands = if (opens == 1) 40 else Int.MAX_VALUE) }
        }
        withTimeout(20_000) { while (opens < 2 || c.state.value.obdState != ObdState.RUNNING) delay(20) }
        job.cancel(); job.join()
        assertEquals(1, sessions.records.size)
        val statuses = outbox.peek(10_000).filter { it.kind == "status" }
            .map { Json.parseToJsonElement(it.payload).jsonObject["status"]!!.jsonObject["state"]!!.jsonPrimitive.content }
        assertTrue(statuses.toString(), statuses.containsAll(listOf("connected", "disconnected")))
        val sids = outbox.peek(10_000).mapNotNull { it.sessionId }.toSet()
        assertEquals(1, sids.size)
    }

    @Test fun `contact coupe - etat NO_VEHICLE publie`() = runBlocking {
        val (c, outbox, _) = setup()
        val sim = FakeElm327(vehicleOn = false)
        val job = launch { c.runSession(GatewaySettings()) { sim.transport() } }
        withTimeout(10_000) { while (c.state.value.obdState != ObdState.NO_VEHICLE) delay(10) }
        job.cancel(); job.join()
        assertTrue(outbox.peek(100).any { it.payload.contains("\"no_vehicle\"") })
    }
}
