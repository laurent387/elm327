package com.overmind.obdgateway.obd

import com.overmind.obdgateway.obd.elm.Elm327Session
import com.overmind.obdgateway.obd.elm.ElmConnection
import com.overmind.obdgateway.obd.elm.NoVehicleException
import com.overmind.obdgateway.obd.elm.ObdProtocol
import com.overmind.obdgateway.obd.elm.ReadStatus
import com.overmind.obdgateway.obd.pid.PidCatalog
import com.overmind.obdgateway.sim.FakeElm327
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327SessionTest {

    private fun session(sim: FakeElm327): Elm327Session {
        val t = sim.transport().apply { open() }
        return Elm327Session(ElmConnection(t))
    }

    @Test fun `initialisation, protocole et PID supportes en CAN`() {
        val sim = FakeElm327(secondEcuSupport = intArrayOf(0x80, 0, 0, 0)) // 7E9 supporte 0101
        val s = session(sim)
        val info = s.initialize()
        assertEquals("ELM327 v1.5", info.version)
        assertTrue(info.headers)
        assertEquals(12.6, info.voltage!!, 1e-9)
        assertEquals(ObdProtocol.CAN_11_500, s.detectProtocol())
        val supported = s.discoverSupportedPids()
        assertTrue(supported.containsAll(listOf("010C", "010B", "0104", "0133", "0101")))
        assertFalse("0120 est un bitmap, pas un PID de donnée", "0120" in supported)
        assertFalse("0150" in supported)
    }

    @Test fun `MVP MAP RPM charge en une requete groupee`() {
        val sim = FakeElm327()
        val s = session(sim).apply { initialize(); detectProtocol(); discoverSupportedPids() }
        val r = s.readPids(PidCatalog.MVP.map { PidCatalog[it]!! }).associateBy { it.def.key }
        assertEquals(3500.0, r["rpm"]!!.value!!, 1e-9)
        assertEquals(98.0, r["map_kpa_absolute"]!!.value!!, 1e-9)
        assertEquals(50.196, r["engine_load_pct"]!!.value!!, 1e-3)
        assertEquals("7E8", r["rpm"]!!.ecu)
        assertTrue(sim.received.contains("010C0B04"))
    }

    @Test fun `repli unitaire si l'adaptateur refuse le multi-PID`() {
        val sim = FakeElm327(multiPidSupported = false)
        val s = session(sim).apply { initialize(); detectProtocol(); discoverSupportedPids() }
        val r = s.readPids(listOf(PidCatalog["010C"]!!, PidCatalog["010D"]!!)).associateBy { it.def.key }
        assertEquals(85.0, r["speed_kmh"]!!.value!!, 1e-9)
        assertFalse(s.multiPidEnabled)
        val n = sim.received.size
        s.readPids(listOf(PidCatalog["010C"]!!, PidCatalog["010D"]!!))
        assertEquals(listOf("010C", "010D"), sim.received.drop(n).sorted())
    }

    @Test fun `PID non supporte ou absent`() {
        val sim = FakeElm327()
        val s = session(sim).apply { initialize(); detectProtocol(); discoverSupportedPids() }
        val maf = s.readPid(PidCatalog["0123"]!!)
        assertEquals(ReadStatus.UNSUPPORTED, maf.status)
        assertNull(maf.value)
        assertFalse("0123" in sim.received.joinToString())
    }

    @Test fun `DTC confirmes, en attente, permanents en CAN`() {
        val sim = FakeElm327(stored = listOf(0x0171, 0x0300), pending = listOf(0x0101), permanentSupported = false)
        val s = session(sim).apply { initialize(); detectProtocol() }
        val d = s.readDtcs()
        assertEquals(listOf("P0171", "P0300"), d.stored.map { it.code })
        assertEquals(listOf("P0101"), d.pending.map { it.code })
        assertNull("mode 0A non supporté → null", d.permanent)
    }

    @Test fun `DTC en KWP (3 codes par message) et VIN`() {
        val sim = FakeElm327(protocol = '5', stored = listOf(0x0171, 0x0172, 0x0300, 0x0420))
        val s = session(sim).apply { initialize(); detectProtocol() }
        assertEquals(ObdProtocol.KWP_FAST, s.info.protocol)
        assertEquals(listOf("P0171", "P0172", "P0300", "P0420"), s.readDtcs().stored.map { it.code })
        val r = s.readPid(PidCatalog["010C"]!!)
        assertEquals(3500.0, r.value!!, 1e-9)
        assertEquals("10", r.ecu)
    }

    @Test fun `VIN CAN multi-trame`() {
        val sim = FakeElm327()
        val s = session(sim).apply { initialize(); detectProtocol() }
        assertEquals("WDB1714561F123456", s.readVin())
    }

    @Test fun `contact coupe = NoVehicleException`() {
        val sim = FakeElm327(vehicleOn = false)
        val s = session(sim).apply { initialize() }
        assertThrows(NoVehicleException::class.java) { s.detectProtocol() }
    }

    @Test fun `effacement des defauts puis relecture`() {
        val sim = FakeElm327(stored = listOf(0x0171), pending = listOf(0x0300))
        val s = session(sim).apply { initialize(); detectProtocol(); discoverSupportedPids() }
        assertEquals(listOf("P0171"), s.readDtcs().stored.map { it.code })
        val after = s.clearDtcs()
        assertTrue(after.stored.isEmpty() && after.pending.isEmpty())
        assertTrue("04" in sim.received)
    }

    @Test fun `effacement refuse par le calculateur`() {
        val sim = FakeElm327(stored = listOf(0x0171), clearRefusal = 0x22)
        val s = session(sim).apply { initialize(); detectProtocol(); discoverSupportedPids() }
        val e = assertThrows(java.io.IOException::class.java) { s.clearDtcs() }
        assertTrue(e.message!!.contains("22"))
        assertEquals(listOf("P0171"), s.readDtcs().stored.map { it.code })
    }
}
