package com.overmind.obdgateway.obd.pid

/**
 * Catalogue des PID mode 01 (SAE J1979) pris en charge par la passerelle.
 * Aligné sur schemas/pid_catalog.json (vérifié par PidCatalogConsistencyTest).
 *
 * [dataBytes] = nombre d'octets de données renvoyés après "41 <pid>".
 * [decode] reçoit uniquement ces octets ; il renvoie null si la trame est
 * inexploitable (jamais de valeur inventée).
 */
data class PidDef(
    val pid: String,          // "010C"
    val key: String,          // "rpm"
    val unit: String,
    val label: String,
    val dataBytes: Int,
    val fast: Boolean = false, // interrogé à chaque cycle
    val decode: (IntArray) -> Double?,
) {
    val pidByte: Int get() = pid.substring(2, 4).toInt(16)
}

object PidCatalog {

    private fun a(f: (Int) -> Double): (IntArray) -> Double? = { d -> if (d.isNotEmpty()) f(d[0]) else null }
    private fun ab(f: (Int, Int) -> Double): (IntArray) -> Double? = { d -> if (d.size >= 2) f(d[0], d[1]) else null }

    /** PID 0170 : octet A = bits de support (bit0 A commandée, bit1 A mesurée...), B-C commandée A, D-E mesurée A (1/32 kPa). */
    private val boostA: (IntArray) -> Double? = { d ->
        if (d.size >= 5 && (d[0] and 0x02) != 0) ((d[3] shl 8) + d[4]) / 32.0 else null
    }

    /** PID 017C : A = support (bit0 entrée B1), B-C = température /10 − 40. */
    private val dpfTempB1Inlet: (IntArray) -> Double? = { d ->
        if (d.size >= 3 && (d[0] and 0x01) != 0) ((d[1] shl 8) + d[2]) / 10.0 - 40.0 else null
    }

    /** PID 017A : A = support (bit0 delta P B1), B-C = pression différentielle signée, 1/100 kPa. */
    private val dpfDeltaB1: (IntArray) -> Double? = { d ->
        if (d.size >= 3 && (d[0] and 0x01) != 0) {
            val raw = (d[1] shl 8) + d[2]
            (if (raw >= 0x8000) raw - 0x10000 else raw) / 100.0
        } else null
    }

    private val trim: (IntArray) -> Double? = a { it * 100.0 / 128.0 - 100.0 }
    private val pct: (IntArray) -> Double? = a { it * 100.0 / 255.0 }
    private val temp: (IntArray) -> Double? = a { it - 40.0 }
    private val lambda: (IntArray) -> Double? = ab { x, y -> ((x shl 8) + y) * 2.0 / 65536.0 }

    val all: List<PidDef> = listOf(
        PidDef("0104", "engine_load_pct", "%", "Charge moteur calculée", 1, fast = true, decode = pct),
        PidDef("0105", "coolant_temp_c", "°C", "Température liquide de refroidissement", 1, decode = temp),
        PidDef("0106", "short_fuel_trim_b1_pct", "%", "Correction court terme banc 1", 1, decode = trim),
        PidDef("0107", "long_fuel_trim_b1_pct", "%", "Correction long terme banc 1", 1, decode = trim),
        PidDef("0108", "short_fuel_trim_b2_pct", "%", "Correction court terme banc 2", 1, decode = trim),
        PidDef("0109", "long_fuel_trim_b2_pct", "%", "Correction long terme banc 2", 1, decode = trim),
        PidDef("010A", "fuel_pressure_kpa", "kPa", "Pression carburant (relative)", 1, decode = a { it * 3.0 }),
        PidDef("010B", "map_kpa_absolute", "kPa", "Pression absolue collecteur (MAP)", 1, fast = true, decode = a { it.toDouble() }),
        PidDef("010C", "rpm", "rpm", "Régime moteur", 2, fast = true, decode = ab { x, y -> ((x shl 8) + y) / 4.0 }),
        PidDef("010D", "speed_kmh", "km/h", "Vitesse véhicule", 1, fast = true, decode = a { it.toDouble() }),
        PidDef("010E", "timing_advance_deg", "°", "Avance à l'allumage (cyl. 1)", 1, decode = a { it / 2.0 - 64.0 }),
        PidDef("010F", "intake_air_temp_c", "°C", "Température air admission", 1, decode = temp),
        PidDef("0110", "maf_g_s", "g/s", "Débit d'air MAF", 2, fast = true, decode = ab { x, y -> ((x shl 8) + y) / 100.0 }),
        PidDef("0111", "throttle_pct", "%", "Position papillon", 1, fast = true, decode = pct),
        PidDef("0114", "o2_b1s1_v", "V", "Sonde O2 B1S1 (tension)", 2, decode = a { it / 200.0 }),
        PidDef("0115", "o2_b1s2_v", "V", "Sonde O2 B1S2 (tension)", 2, decode = a { it / 200.0 }),
        PidDef("0118", "o2_b2s1_v", "V", "Sonde O2 B2S1 (tension)", 2, decode = a { it / 200.0 }),
        PidDef("0119", "o2_b2s2_v", "V", "Sonde O2 B2S2 (tension)", 2, decode = a { it / 200.0 }),
        PidDef("011F", "run_time_s", "s", "Temps depuis démarrage", 2, decode = ab { x, y -> ((x shl 8) + y).toDouble() }),
        PidDef("0122", "fuel_rail_pressure_rel_kpa", "kPa", "Pression rampe (relative collecteur)", 2, decode = ab { x, y -> ((x shl 8) + y) * 0.079 }),
        PidDef("0123", "fuel_rail_pressure_kpa", "kPa", "Pression rampe carburant (jauge)", 2, decode = ab { x, y -> ((x shl 8) + y) * 10.0 }),
        PidDef("0124", "lambda_b1s1", "λ", "Sonde large bande B1S1 (lambda)", 4, decode = lambda),
        PidDef("012C", "egr_commanded_pct", "%", "EGR commandé", 1, decode = pct),
        PidDef("012D", "egr_error_pct", "%", "Erreur EGR", 1, decode = trim),
        PidDef("012F", "fuel_level_pct", "%", "Niveau carburant", 1, decode = pct),
        PidDef("0133", "baro_kpa", "kPa", "Pression atmosphérique (BARO)", 1, decode = a { it.toDouble() }),
        PidDef("0134", "lambda_b1s1_wide", "λ", "Sonde large bande B1S1 (courant)", 4, decode = lambda),
        PidDef("013C", "cat_temp_b1s1_c", "°C", "Température catalyseur B1S1", 2, decode = ab { x, y -> ((x shl 8) + y) / 10.0 - 40.0 }),
        PidDef("0142", "control_module_v", "V", "Tension module de commande", 2, decode = ab { x, y -> ((x shl 8) + y) / 1000.0 }),
        PidDef("0143", "absolute_load_pct", "%", "Charge absolue", 2, decode = ab { x, y -> ((x shl 8) + y) * 100.0 / 255.0 }),
        PidDef("0144", "commanded_lambda", "λ", "Lambda commandé", 2, decode = lambda),
        PidDef("0145", "relative_throttle_pct", "%", "Position papillon relative", 1, decode = pct),
        PidDef("0146", "ambient_air_temp_c", "°C", "Température air ambiant", 1, decode = temp),
        PidDef("0149", "accel_pedal_d_pct", "%", "Pédale d'accélérateur D", 1, decode = pct),
        PidDef("015C", "oil_temp_c", "°C", "Température huile moteur", 1, decode = temp),
        PidDef("015E", "fuel_rate_l_h", "L/h", "Débit carburant moteur", 2, decode = ab { x, y -> ((x shl 8) + y) / 20.0 }),
        PidDef("0170", "boost_a_kpa_absolute", "kPa", "Suralimentation A mesurée (absolue)", 10, decode = boostA),
        PidDef("017A", "dpf_diff_pressure_b1_kpa", "kPa", "Pression différentielle FAP B1", 7, decode = dpfDeltaB1),
        PidDef("017C", "dpf_temp_b1_inlet_c", "°C", "Température entrée FAP B1", 9, decode = dpfTempB1Inlet),
    )

    private val byPid = all.associateBy { it.pid }
    private val byByte = all.associateBy { it.pidByte }

    operator fun get(pid: String): PidDef? = byPid[pid.uppercase()]
    fun byPidByte(b: Int): PidDef? = byByte[b]

    /** PID MVP prioritaires (dans l'ordre d'affichage du tableau de bord). */
    val MVP = listOf("010C", "010B", "0104")

    const val BOOST_KEY = "boost_kpa_relative"
}
