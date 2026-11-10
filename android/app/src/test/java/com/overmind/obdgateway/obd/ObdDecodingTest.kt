package com.overmind.obdgateway.obd

import com.overmind.obdgateway.obd.dtc.DtcDecoder
import com.overmind.obdgateway.obd.elm.EcuPayload
import com.overmind.obdgateway.obd.elm.ElmConnection
import com.overmind.obdgateway.obd.elm.ElmError
import com.overmind.obdgateway.obd.elm.ObdCommandGuard
import com.overmind.obdgateway.obd.elm.ObdProtocol
import com.overmind.obdgateway.obd.elm.ObdResponseParser
import com.overmind.obdgateway.obd.pid.PidCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDecodingTest {

    private fun dec(pid: String, vararg b: Int) = PidCatalog[pid]!!.decode(b)

    @Test fun `formules SAE J1979`() {
        assertEquals(3500.0, dec("010C", 0x36, 0xB0)!!, 1e-9)
        assertEquals(98.0, dec("010B", 0x62)!!, 1e-9)
        assertEquals(100.0, dec("0104", 0xFF)!!, 1e-9)
        assertEquals(89.0, dec("0105", 0x81)!!, 1e-9)
        assertEquals(4.6875, dec("0106", 0x86)!!, 1e-9)
        assertEquals(-100.0, dec("0107", 0x00)!!, 1e-9)
        assertEquals(20.0, dec("0110", 0x07, 0xD0)!!, 1e-9)
        assertEquals(-64.0, dec("010E", 0x00)!!, 1e-9)
        assertEquals(1.0, dec("0144", 0x80, 0x00)!!, 1e-9)
        assertEquals(12.5, dec("0142", 0x30, 0xD4)!!, 1e-9)
    }

    @Test fun `trame trop courte = pas de valeur inventee`() {
        assertNull(dec("010C", 0x36))
        assertNull(dec("0104"))
        assertNull(dec("0170", 0x01, 0, 0, 0, 0)) // A mesurée non supportée (bit1 absent)
        assertEquals(150.0, dec("0170", 0x03, 0, 0, 0x12, 0xC0)!!, 1e-9)
        assertEquals(-1.0, dec("017A", 0x01, 0xFF, 0x9C)!!, 1e-9)
    }

    @Test fun `CAN 11 bits avec en-tetes, plusieurs calculateurs`() {
        val p = ObdResponseParser.parse(
            listOf("7E8 06 41 00 BE 3F A8 13 00", "7E9 06 41 00 98 18 80 10 00"), ObdProtocol.CAN_11_500, headers = true,
        )
        assertEquals(2, p.size)
        assertEquals("7E8", p[0].ecu)
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13), p[0].bytes.toList())
    }

    @Test fun `CAN 11 bits sans espaces`() {
        val p = ObdResponseParser.parse(listOf("7E804410C1AF8"), ObdProtocol.CAN_11_500, headers = true)
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), p.single().bytes.toList())
    }

    @Test fun `ISO-TP multi-trame VIN`() {
        val lines = listOf(
            "7E8 10 14 49 02 01 57 44 42", "7E8 21 31 37 31 34 35 36 31", "7E8 22 46 31 32 33 34 35 36",
        )
        val p = ObdResponseParser.parse(lines, ObdProtocol.CAN_11_500, headers = true).single()
        assertEquals(20, p.bytes.size)
        assertEquals("WDB1714561F123456", p.bytes.drop(3).map { it.toChar() }.joinToString(""))
    }

    @Test fun `CAN 29 bits`() {
        val p = ObdResponseParser.parse(listOf("18 DA F1 10 03 41 0D 55"), ObdProtocol.CAN_29_500, headers = true).single()
        assertEquals("10", p.ecu)
        assertEquals(listOf(0x41, 0x0D, 0x55), p.bytes.toList())
    }

    @Test fun `KWP avec en-tetes, checksum retire`() {
        val p = ObdResponseParser.parse(listOf("48 6B 10 41 0C 1A F8 C3"), ObdProtocol.KWP_FAST, headers = true).single()
        assertEquals("10", p.ecu)
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), p.bytes.toList())
    }

    @Test fun `CAN sans en-tetes multi-trame`() {
        val p = ObdResponseParser.parse(listOf("014", "0: 49 02 01 57 44 42", "1: 31 37 31 34 35 36 31", "2: 46 31 32 33 34 35 36"),
            ObdProtocol.CAN_11_500, headers = false).single()
        assertEquals(20, p.bytes.size)
    }

    @Test fun `decodage DTC`() {
        assertEquals("P0101", DtcDecoder.decode(0x01, 0x01))
        assertEquals("C1234", DtcDecoder.decode(0x52, 0x34))
        assertEquals("B0001", DtcDecoder.decode(0x80, 0x01))
        assertEquals("U0100", DtcDecoder.decode(0xC1, 0x00))
        assertNull(DtcDecoder.decode(0, 0))
        val can = DtcDecoder.parse(listOf(EcuPayload("7E8", intArrayOf(0x43, 0x02, 0x01, 0x71, 0x03, 0x00))), 0x43, isCan = true)
        assertEquals(listOf("P0171", "P0300"), can.map { it.code })
        val kwp = DtcDecoder.parse(listOf(EcuPayload("10", intArrayOf(0x43, 0x01, 0x71, 0x00, 0x00, 0x00, 0x00))), 0x43, isCan = false)
        assertEquals(listOf("P0171"), kwp.map { it.code })
    }

    @Test fun `garde-fou lecture seule`() {
        for (ok in listOf("ATZ", "ATE0", "ATH1", "ATSP0", "ATDPN", "ATRV", "010C", "010C0B04", "03", "07", "0A", "0902", "AT ST 32", "atsp6"))
            ObdCommandGuard.check(ok)
        for (bad in listOf("04", "08", "2F0001", "3101", "1003", "2E F1 90 00", "ATPP 0C SV 23", "ATMA", "ATSH 7E0",
                "010C0B0405060708", "AT@3 X", "01", "0", "", "03 00", "ATBD"))
            assertThrows(bad, ObdCommandGuard.ForbiddenCommandException::class.java) { ObdCommandGuard.check(bad) }
    }

    @Test fun `nettoyage reponse ELM`() {
        val lines = ElmConnection.clean("0100", "0100\r\nSEARCHING...\r\n7E8 06 41 00 BE 3F A8 13\r\n\r\n")
        assertEquals(listOf("7E8 06 41 00 BE 3F A8 13"), lines)
        assertEquals(ElmError.UNABLE_TO_CONNECT, ElmError.detect(ElmConnection.clean("0100", "SEARCHING...\rUNABLE TO CONNECT\r")))
        assertEquals(ElmError.BUS_ERROR, ElmError.detect(ElmConnection.clean("0100", "BUS INIT: ...ERROR\r")))
        assertEquals(ElmError.UNKNOWN_COMMAND, ElmError.detect(listOf("?")))
        assertTrue(ElmError.detect(listOf("41 0C 1A F8")) == null)
    }
}
