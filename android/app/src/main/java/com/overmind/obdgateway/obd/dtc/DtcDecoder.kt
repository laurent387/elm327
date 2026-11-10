package com.overmind.obdgateway.obd.dtc

import com.overmind.obdgateway.obd.elm.EcuPayload

data class Dtc(val code: String, val ecu: String?)

/** Décodage SAE J1979 des réponses 43 / 47 / 4A. */
object DtcDecoder {

    private val letters = charArrayOf('P', 'C', 'B', 'U')

    fun decode(hi: Int, lo: Int): String? {
        if (hi == 0 && lo == 0) return null
        val letter = letters[(hi shr 6) and 0x03]
        val d1 = (hi shr 4) and 0x03
        val d2 = hi and 0x0F
        return "%c%d%X%02X".format(letter, d1, d2, lo)
    }

    /**
     * @param responseSid 0x43, 0x47 ou 0x4A
     * @param isCan sur CAN le 2e octet est le nombre de codes ; sur les anciens protocoles
     *              chaque message porte 3 codes (complétés par 00 00).
     */
    fun parse(payloads: List<EcuPayload>, responseSid: Int, isCan: Boolean): List<Dtc> {
        val out = LinkedHashSet<Dtc>()
        for (p in payloads) {
            val b = p.bytes
            if (b.isEmpty() || b[0] != responseSid) continue
            val start = if (isCan) 2 else 1
            val count = if (isCan && b.size >= 2) b[1] else Int.MAX_VALUE
            var i = start
            var n = 0
            while (i + 1 < b.size && n < count) {
                decode(b[i], b[i + 1])?.let { out += Dtc(it, p.ecu) }
                i += 2; n++
            }
        }
        return out.toList()
    }
}
