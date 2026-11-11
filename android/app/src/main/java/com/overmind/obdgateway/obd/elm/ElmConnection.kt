package com.overmind.obdgateway.obd.elm

import com.overmind.obdgateway.obd.transport.ObdTransport
import java.io.IOException

/** Réponse brute de l'ELM, découpée en lignes utiles. */
data class ElmResponse(val command: String, val lines: List<String>, val raw: String, val elapsedMs: Long) {
    val error: ElmError? = ElmError.detect(lines)
    val isOk: Boolean get() = error == null && lines.isNotEmpty()
}

enum class ElmError(val token: String) {
    NO_DATA("NO DATA"),
    UNKNOWN_COMMAND("?"),
    UNABLE_TO_CONNECT("UNABLE TO CONNECT"),
    CAN_ERROR("CAN ERROR"),
    BUS_ERROR("BUS ERROR"),
    BUS_BUSY("BUS BUSY"),
    BUFFER_FULL("BUFFER FULL"),
    DATA_ERROR("DATA ERROR"),
    STOPPED("STOPPED"),
    FB_ERROR("FB ERROR"),
    LOW_VOLTAGE_RESET("LV RESET"),
    ERR("ERR"),
    TIMEOUT("<timeout>");

    companion object {
        fun detect(lines: List<String>): ElmError? {
            if (lines.isEmpty()) return NO_DATA
            for (l in lines) {
                val u = l.uppercase()
                for (e in entries) {
                    if (e == ERR) { if (Regex("^ERR\\d+").containsMatchIn(u)) return ERR; continue }
                    if (e == UNKNOWN_COMMAND) { if (u == "?") return e; continue }
                    if (u.contains(e.token)) return e
                }
            }
            return null
        }
    }
}

class ElmTimeoutException(cmd: String, partial: String) : IOException("Pas de prompt '>' après $cmd (reçu: ${partial.take(80)})")

/**
 * Échange commande/réponse avec un ELM327 : écrit "<cmd>\r", lit jusqu'au prompt '>'.
 * Toute commande passe par [ObdCommandGuard] (lecture seule).
 */
class ElmConnection(private val transport: ObdTransport) {

    private val buf = ByteArray(1024)

    @Synchronized
    fun send(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ElmResponse =
        exchange(ObdCommandGuard.check(command), timeoutMs)

    /** Effacement des codes défauts (mode 04) : seul chemin d'écriture, voir [ObdCommandGuard.checkClearDtc]. */
    @Synchronized
    fun sendClearDtc(timeoutMs: Long = 10_000): ElmResponse = exchange(ObdCommandGuard.checkClearDtc("04"), timeoutMs)

    private fun exchange(cmd: String, timeoutMs: Long): ElmResponse {
        drain()
        val t0 = System.nanoTime()
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val deadline = t0 + timeoutMs * 1_000_000
        while (true) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw ElmTimeoutException(cmd, sb.toString())
            val n = transport.read(buf, remaining)
            if (n < 0) throw IOException("Flux ELM fermé")
            if (n > 0) {
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c == '>') {
                        val raw = sb.toString()
                        return ElmResponse(cmd, clean(cmd, raw), raw, (System.nanoTime() - t0) / 1_000_000)
                    }
                    if (c != '\u0000') sb.append(c)
                }
            }
        }
    }

    /** Vide les octets résiduels (réponse tardive d'une commande précédente). */
    private fun drain() {
        var guard = 0
        while (guard++ < 16 && transport.read(buf, 1) > 0) { /* jeter */ }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_500L
        const val RESET_TIMEOUT_MS = 6_000L
        const val SEARCH_TIMEOUT_MS = 12_000L

        fun clean(cmd: String, raw: String): List<String> =
            raw.split('\r', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.replace(" ", "").equals(cmd, ignoreCase = true) } // écho
                .filterNot { it.uppercase().startsWith("SEARCHING") }
                .mapNotNull { l ->
                    val u = l.uppercase()
                    when {
                        u.startsWith("BUS INIT") && u.contains("ERROR") -> "BUS ERROR"
                        u.startsWith("BUS INIT") -> null
                        else -> l
                    }
                }
    }
}
