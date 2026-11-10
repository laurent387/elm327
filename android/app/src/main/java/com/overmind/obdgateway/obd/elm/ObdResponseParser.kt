package com.overmind.obdgateway.obd.elm

/** Protocoles ELM327 (numéro renvoyé par ATDPN, préfixe "A" = détecté automatiquement). */
enum class ObdProtocol(val code: Char, val label: String) {
    AUTO('0', "Automatique"),
    J1850_PWM('1', "SAE J1850 PWM (41.6 kbaud)"),
    J1850_VPW('2', "SAE J1850 VPW (10.4 kbaud)"),
    ISO_9141_2('3', "ISO 9141-2 (5 baud init)"),
    KWP_5BAUD('4', "ISO 14230-4 KWP (5 baud init)"),
    KWP_FAST('5', "ISO 14230-4 KWP (fast init)"),
    CAN_11_500('6', "ISO 15765-4 CAN (11 bit ID, 500 kbaud)"),
    CAN_29_500('7', "ISO 15765-4 CAN (29 bit ID, 500 kbaud)"),
    CAN_11_250('8', "ISO 15765-4 CAN (11 bit ID, 250 kbaud)"),
    CAN_29_250('9', "ISO 15765-4 CAN (29 bit ID, 250 kbaud)"),
    J1939('A', "SAE J1939 CAN (29 bit ID, 250 kbaud)"),
    USER1_CAN('B', "USER1 CAN"),
    USER2_CAN('C', "USER2 CAN");

    val isCan: Boolean get() = this in setOf(CAN_11_500, CAN_29_500, CAN_11_250, CAN_29_250, USER1_CAN, USER2_CAN)
    val isCan29: Boolean get() = this == CAN_29_500 || this == CAN_29_250

    companion object {
        /** "A6" / "6" → CAN_11_500. */
        fun fromDpn(dpn: String): ObdProtocol? {
            val c = dpn.trim().uppercase().removePrefix("A").firstOrNull() ?: return null
            return entries.firstOrNull { it.code == c }
        }
    }
}

/** Charge utile OBD (après en-têtes, PCI et checksum) d'un calculateur. */
class EcuPayload(val ecu: String?, val bytes: IntArray) {
    override fun toString(): String = "${ecu ?: "?"}:" + bytes.joinToString(" ") { "%02X".format(it) }
}

/**
 * Transforme les lignes ELM en charges utiles par calculateur.
 * Gère : CAN 11/29 bits avec ou sans en-têtes (ISO-TP mono et multi-trame),
 * J1850/ISO9141/KWP avec ou sans en-têtes (suppression du checksum).
 */
object ObdResponseParser {

    private val indexed = Regex("^([0-9A-F]):\\s*(.*)$")

    fun parse(lines: List<String>, protocol: ObdProtocol, headers: Boolean): List<EcuPayload> {
        val clean = lines.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        return when {
            protocol.isCan && headers -> parseCanWithHeaders(clean, protocol.isCan29)
            protocol.isCan -> parseCanNoHeaders(clean)
            headers -> clean.mapNotNull { parseLegacyWithHeaders(it) }
            else -> clean.mapNotNull { l -> hexBytes(l)?.let { EcuPayload(null, it) } }
        }
    }

    private fun hexBytes(s: String): IntArray? {
        val compact = s.replace(" ", "")
        if (compact.isEmpty() || compact.length % 2 != 0 || !compact.all { it.isDigit() || it in 'A'..'F' }) return null
        return IntArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    private fun parseLegacyWithHeaders(line: String): EcuPayload? {
        val b = hexBytes(line) ?: return null
        if (b.size < 5) return null // 3 octets d'en-tête + ≥1 donnée + checksum
        return EcuPayload("%02X".format(b[2]), b.copyOfRange(3, b.size - 1))
    }

    private class Assembly(val ecu: String, val total: Int) {
        val data = ArrayList<Int>()
    }

    private fun parseCanWithHeaders(lines: List<String>, can29: Boolean): List<EcuPayload> {
        val out = ArrayList<EcuPayload>()
        val pending = LinkedHashMap<String, Assembly>()
        for (line in lines) {
            val tokens = line.split(' ').filter { it.isNotEmpty() }
            val ecu: String
            val bytes: IntArray
            if (!can29 && tokens.isNotEmpty() && tokens[0].length == 3) {
                ecu = tokens[0]
                bytes = hexBytes(tokens.drop(1).joinToString("")) ?: continue
            } else if (!can29 && tokens.size == 1 && tokens[0].length > 3) { // ATS0 : "7E8064100BE3FA813"
                ecu = tokens[0].substring(0, 3)
                bytes = hexBytes(tokens[0].substring(3)) ?: continue
            } else {
                val all = hexBytes(line) ?: continue
                if (all.size < 5) continue
                ecu = "%02X".format(all[3])
                bytes = all.copyOfRange(4, all.size)
            }
            if (bytes.isEmpty()) continue
            val pci = bytes[0] shr 4
            when (pci) {
                0 -> { // trame unique
                    val len = bytes[0] and 0x0F
                    if (len in 1..(bytes.size - 1)) out += EcuPayload(ecu, bytes.copyOfRange(1, 1 + len))
                }
                1 -> { // première trame
                    if (bytes.size < 2) continue
                    val total = ((bytes[0] and 0x0F) shl 8) or bytes[1]
                    val a = Assembly(ecu, total)
                    for (i in 2 until bytes.size) a.data += bytes[i]
                    pending[ecu] = a
                }
                2 -> { // trame consécutive
                    val a = pending[ecu] ?: continue
                    for (i in 1 until bytes.size) a.data += bytes[i]
                    if (a.data.size >= a.total) {
                        out += EcuPayload(ecu, a.data.take(a.total).toIntArray())
                        pending.remove(ecu)
                    }
                }
                else -> {} // contrôle de flux : ignoré
            }
        }
        return out
    }

    private fun parseCanNoHeaders(lines: List<String>): List<EcuPayload> {
        val out = ArrayList<EcuPayload>()
        var expected = -1
        val multi = ArrayList<Int>()
        for (line in lines) {
            val m = indexed.find(line)
            when {
                m != null -> hexBytes(m.groupValues[2])?.let { multi.addAll(it.toList()) }
                line.replace(" ", "").length == 3 && expected < 0 && line.replace(" ", "").all { it.isDigit() || it in 'A'..'F' } ->
                    expected = line.replace(" ", "").toInt(16)
                else -> hexBytes(line)?.let { out += EcuPayload(null, it) }
            }
        }
        if (multi.isNotEmpty()) {
            val n = if (expected > 0) minOf(expected, multi.size) else multi.size
            out += EcuPayload(null, multi.take(n).toIntArray())
        }
        return out
    }
}
