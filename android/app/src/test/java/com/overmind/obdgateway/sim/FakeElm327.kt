package com.overmind.obdgateway.sim

import com.overmind.obdgateway.obd.transport.ObdTransport
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Simulateur ELM327 en mémoire pour les tests JVM. Reproduit le dialogue texte d'un vrai
 * adaptateur (écho, en-têtes, espaces, prompt '>'), CAN 11 bits ou KWP, multi-calculateurs.
 */
class FakeElm327(
    var protocol: Char = '6',             // '6' CAN 11/500, '5' KWP fast
    var vehicleOn: Boolean = true,
    /** PID mode 01 → octets de données renvoyés par le calculateur moteur. */
    val pids: MutableMap<Int, IntArray> = defaultPids(),
    /** Réponses d'un 2e calculateur (ex. boîte 7E9) pour 0100. */
    var secondEcuSupport: IntArray? = null,
    var stored: List<Int> = emptyList(),   // DTC bruts 16 bits
    var pending: List<Int> = emptyList(),
    var permanentSupported: Boolean = true,
    var vin: String = "WDB1714561F123456",
    var multiPidSupported: Boolean = true,
) {
    var echo = true
    var headers = false
    var spaces = true
    var linefeeds = true
    val received = ArrayList<String>()

    companion object {
        fun defaultPids(): MutableMap<Int, IntArray> = mutableMapOf(
            0x04 to intArrayOf(0x80),         // 50.2 %
            0x05 to intArrayOf(0x7B),         // 83 °C
            0x06 to intArrayOf(0x86),         // +4.7 %
            0x07 to intArrayOf(0x80),         // 0 %
            0x0B to intArrayOf(0x62),         // 98 kPa
            0x0C to intArrayOf(0x36, 0xB0),   // 3500 rpm
            0x0D to intArrayOf(0x55),         // 85 km/h
            0x0F to intArrayOf(0x41),         // 25 °C
            0x10 to intArrayOf(0x07, 0xD0),   // 20.00 g/s
            0x11 to intArrayOf(0x33),         // 20 %
            0x33 to intArrayOf(0x65),         // 101 kPa
        )
    }

    private fun supportBitmap(base: Int): IntArray {
        val bits = IntArray(4)
        val keys = pids.keys + (if (base < 0xE0) listOf(base + 0x20) else emptyList())
        val hasNext = pids.keys.any { it > base + 0x20 }
        for (pid in keys) {
            if (pid <= base || pid > base + 0x20) continue
            if (pid == base + 0x20 && !hasNext) continue
            val i = pid - base - 1
            bits[i / 8] = bits[i / 8] or (0x80 shr (i % 8))
        }
        return bits
    }

    private fun hex(b: IntArray) = b.joinToString(if (spaces) " " else "") { "%02X".format(it) }

    /** Encadre une charge utile OBD selon le protocole et ATH. */
    private fun frame(payload: IntArray, ecu: String = "7E8"): List<String> {
        val isCan = protocol in "6789"
        if (!isCan) {
            val p = if (headers) intArrayOf(0x48, 0x6B, if (ecu == "7E8") 0x10 else 0x18) + payload + intArrayOf(0x00) else payload
            return listOf(hex(p))
        }
        if (payload.size <= 7) {
            val body = intArrayOf(payload.size) + payload
            return listOf(if (headers) ecu + (if (spaces) " " else "") + hex(body) else hex(payload))
        }
        // ISO-TP multi-trame
        val lines = ArrayList<String>()
        if (!headers) {
            lines += "%03X".format(payload.size)
            var idx = 0; var n = 0
            while (idx < payload.size) {
                val chunk = payload.copyOfRange(idx, minOf(payload.size, idx + (if (n == 0) 6 else 7)))
                lines += "$n: " + hex(chunk); idx += chunk.size; n = (n + 1) % 16
            }
            return lines
        }
        val first = intArrayOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.copyOfRange(0, 6)
        lines += ecu + " " + hex(first)
        var idx = 6; var sn = 1
        while (idx < payload.size) {
            val chunk = payload.copyOfRange(idx, minOf(payload.size, idx + 7))
            lines += ecu + " " + hex(intArrayOf(0x20 or sn) + chunk)
            idx += chunk.size; sn = (sn + 1) % 16
        }
        return lines
    }

    fun handle(cmdRaw: String): List<String> {
        val cmd = cmdRaw.trim().uppercase().replace(" ", "")
        received += cmd
        if (cmd.startsWith("AT")) {
            val b = cmd.substring(2)
            return when {
                b == "Z" -> { echo = true; headers = false; spaces = true; listOf("", "ELM327 v1.5") }
                b == "I" -> listOf("ELM327 v1.5")
                b == "@1" -> listOf("OBDII to RS232 Interpreter")
                b == "RV" -> listOf("12.6V")
                b == "DPN" -> listOf("A$protocol")
                b == "E0" -> { echo = false; listOf("OK") }
                b == "H1" -> { headers = true; listOf("OK") }
                b == "H0" -> { headers = false; listOf("OK") }
                b == "S0" -> { spaces = false; listOf("OK") }
                b == "S1" -> { spaces = true; listOf("OK") }
                else -> listOf("OK")
            }
        }
        if (!vehicleOn) return listOf("SEARCHING...", "UNABLE TO CONNECT")
        val mode = cmd.substring(0, 2)
        return when (mode) {
            "01" -> mode01(cmd)
            "03" -> dtcResponse(0x43, stored)
            "07" -> dtcResponse(0x47, pending)
            "0A" -> if (permanentSupported) dtcResponse(0x4A, emptyList()) else listOf("NO DATA")
            "09" -> if (cmd == "0902") frame(intArrayOf(0x49, 0x02, 0x01) + vin.map { it.code }.toIntArray()) else listOf("NO DATA")
            else -> listOf("?")
        }
    }

    private fun mode01(cmd: String): List<String> {
        val requested = (2 until cmd.length step 2).map { cmd.substring(it, it + 2).toInt(16) }
        if (requested.size > 1 && !multiPidSupported) return listOf("NO DATA")
        val out = ArrayList<Int>(); out += 0x41
        for (pid in requested) {
            val data = if (pid % 0x20 == 0) supportBitmap(pid) else pids[pid]
            if (data != null) { out += pid; out.addAll(data.toList()) }
        }
        if (out.size == 1) return listOf("NO DATA")
        val lines = ArrayList(frame(out.toIntArray()))
        if (requested == listOf(0x00) && secondEcuSupport != null && headers) {
            lines += frame(intArrayOf(0x41, 0x00) + secondEcuSupport!!, "7E9")
        }
        return lines
    }

    private fun dtcResponse(sid: Int, codes: List<Int>): List<String> {
        val isCan = protocol in "6789"
        if (isCan) {
            val p = intArrayOf(sid, codes.size) + codes.flatMap { listOf(it shr 8, it and 0xFF) }
            return frame(p)
        }
        if (codes.isEmpty()) return listOf("NO DATA")
        return codes.chunked(3).flatMap { chunk ->
            val padded = chunk + List(3 - chunk.size) { 0 }
            frame(intArrayOf(sid) + padded.flatMap { listOf(it shr 8, it and 0xFF) })
        }
    }

    /** Transport branché sur ce simulateur. */
    fun transport(dropAfterCommands: Int = Int.MAX_VALUE): ObdTransport = object : ObdTransport {
        private val rx = LinkedBlockingQueue<Byte>()
        private val line = StringBuilder()
        private var open = false
        private var count = 0
        override val description = "FakeELM"
        override val isOpen get() = open
        override fun open() { open = true }
        override fun close() { open = false }
        override fun write(bytes: ByteArray) {
            if (!open) throw IOException("fermé")
            for (b in bytes) {
                val c = b.toInt().toChar()
                if (c == '\r') {
                    val cmd = line.toString(); line.clear()
                    if (++count > dropAfterCommands) { open = false; throw IOException("Bluetooth perdu") }
                    val sep = if (linefeeds) "\r\n" else "\r"
                    val sb = StringBuilder()
                    if (echo) sb.append(cmd).append(sep)
                    handle(cmd).forEach { sb.append(it).append(sep) }
                    sb.append(sep).append('>')
                    sb.toString().toByteArray().forEach { rx.put(it) }
                } else line.append(c)
            }
        }
        override fun read(buffer: ByteArray, timeoutMs: Long): Int {
            if (!open) return -1
            val first = rx.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return 0
            buffer[0] = first
            var n = 1
            while (n < buffer.size) { val b = rx.poll() ?: break; buffer[n++] = b }
            return n
        }
    }
}
