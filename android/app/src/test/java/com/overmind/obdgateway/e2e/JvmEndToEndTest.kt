package com.overmind.obdgateway.e2e

import com.overmind.obdgateway.buffer.InMemoryOutbox
import com.overmind.obdgateway.gateway.GatewayController
import com.overmind.obdgateway.gateway.MemSessions
import com.overmind.obdgateway.gateway.ObdState
import com.overmind.obdgateway.net.OutboxDrainer
import com.overmind.obdgateway.net.PahoMqttLink
import com.overmind.obdgateway.obd.transport.TcpTransport
import com.overmind.obdgateway.settings.AdapterTransport
import com.overmind.obdgateway.settings.GatewaySettings
import com.overmind.obdgateway.telemetry.MessageFactory
import com.overmind.obdgateway.telemetry.SequenceSource
import com.overmind.obdgateway.telemetry.VehicleIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Bout-en-bout avec le VRAI code de l'appli (TcpTransport, Elm327Session, MessageFactory,
 * OutboxDrainer, PahoMqttLink) contre le simulateur Python et un broker MQTT réel.
 * Lancé par tools/e2e_jvm.sh (sinon ignoré) :
 *   OVERMIND_E2E_ELM=127.0.0.1:35001 OVERMIND_E2E_MQTT=127.0.0.1:1884
 */
class JvmEndToEndTest {
    @Test fun `ELM327 simule vers broker MQTT reel`() = runBlocking {
        val elm = System.getenv("OVERMIND_E2E_ELM")
        val mqtt = System.getenv("OVERMIND_E2E_MQTT")
        assumeTrue("OVERMIND_E2E_ELM / OVERMIND_E2E_MQTT non définis", elm != null && mqtt != null)
        val (eh, ep) = elm!!.split(":")
        val (mh, mp) = mqtt!!.split(":")
        val settings = GatewaySettings(
            brokerHost = mh, brokerPort = mp.toInt(), vehicleId = "slk_r171", gatewayId = "android_01",
            adapterTransport = AdapterTransport.TCP, tcpHost = eh, tcpPort = ep.toInt(), pollIntervalMs = 200,
            aspiration = "supercharger",
        )
        val outbox = InMemoryOutbox()
        var seq = System.currentTimeMillis()
        val factory = MessageFactory(
            { VehicleIdentity(settings.vehicleId, settings.gatewayId, "petrol", settings.aspiration, settings.mapSensorLocation) },
            object : SequenceSource { override fun next() = ++seq },
        )
        val drainer = OutboxDrainer(outbox, { PahoMqttLink(settings, factory.lastWill()) }, log = ::println)
        val controller = GatewayController(outbox, MemSessions(), drainer, factory)
        val drain = launch { drainer.run() }
        val session = launch { controller.runSession(settings) { TcpTransport(settings.tcpHost, settings.tcpPort) } }
        withTimeout(30_000) { while (controller.state.value.obdState != ObdState.RUNNING) delay(50) }
        controller.publishHealth(mapOf("app_version" to "e2e-jvm", "network" to "loopback"))
        delay(4_000)
        session.cancel(); session.join()
        withTimeout(20_000) { while (outbox.count() > 0) { drainer.wake(); delay(100) } }
        drain.cancel()
        val st = drainer.state.value
        println("E2E envoyés=${st.sentTotal} live=${controller.state.value.live.filterValues { it.status == "ok" }.keys}")
        assertEquals(0, outbox.count())
        assertTrue(st.sentTotal >= 10)
        assertTrue(controller.state.value.live["rpm"]?.value != null)
    }
}
