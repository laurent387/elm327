package com.overmind.obdgateway.obd.elm

/**
 * Garde-fou. Toute commande envoyée à l'ELM327 passe par ici. En dehors d'UNE exception
 * (effacement des défauts, voir [checkClearDtc]), tout est en lecture seule.
 *
 * Autorisé : configuration non persistante de l'ELM (AT…) et services OBD de lecture
 * 01 (données courantes), 03 (DTC confirmés), 07 (DTC en attente), 09 (VIN / infos),
 * 0A (DTC permanents).
 * Interdit : 04 (effacement des défauts), 08 (actionneurs), 02 (freeze frame : lecture mais
 * non utilisée), 05/06 (moniteurs, non utilisés), tous les services UDS/KWP (10, 11, 14,
 * 22, 27, 2E, 2F, 31, 3B, 3E…), ATPP (paramètres persistants de l'ELM), ATMA (écoute bus),
 * ATBD/ATBI/ATSH etc. Il n'existe aucun chemin réseau → ELM : cette garde est une défense
 * en profondeur.
 */
object ObdCommandGuard {

    private val allowedAt = listOf(
        "Z", "WS", "D", "E0", "E1", "L0", "L1", "S0", "S1", "H0", "H1", "M0",
        "SP0", "SPA", "DPN", "DP", "RV", "I", "@1", "AT0", "AT1", "AT2", "CAF1",
    )
    private val allowedAtPrefixes = listOf("ST", "TP", "SP") // ATSTxx (timeout), ATTPx / ATSPx (protocole)

    private val allowedObdModes = setOf("01", "03", "07", "09", "0A")
    private val hex = Regex("^[0-9A-F]+$")

    class ForbiddenCommandException(cmd: String) : SecurityException("Commande OBD interdite (lecture seule) : $cmd")

    /**
     * Seule commande d'écriture autorisée : « 04 » (effacement des codes défauts, norme OBD2).
     * Elle n'est accessible que par [ElmConnection.sendClearDtc], appelé uniquement depuis le
     * bouton de l'écran Défauts sur le téléphone/l'autoradio, après confirmation, véhicule à
     * l'arrêt moteur coupé. Aucun chemin réseau (MQTT, MCP, Kitt) n'y mène.
     */
    fun checkClearDtc(raw: String): String {
        val cmd = raw.trim().uppercase().replace(" ", "")
        if (cmd != "04") throw ForbiddenCommandException(raw)
        return cmd
    }

    fun check(raw: String): String {
        val cmd = raw.trim().uppercase().replace(" ", "")
        if (cmd.isEmpty()) throw ForbiddenCommandException(raw)
        if (cmd.startsWith("AT")) {
            val body = cmd.substring(2)
            val ok = body in allowedAt ||
                allowedAtPrefixes.any { body.startsWith(it) && body.length in 3..4 && hex.matches(body.substring(2)) }
            if (!ok) throw ForbiddenCommandException(raw)
            return cmd
        }
        if (!hex.matches(cmd) || cmd.length < 2 || cmd.length % 2 != 0) throw ForbiddenCommandException(raw)
        val mode = cmd.substring(0, 2)
        if (mode !in allowedObdModes) throw ForbiddenCommandException(raw)
        when (mode) {
            "01" -> if (cmd.length !in 4..14) throw ForbiddenCommandException(raw) // 1 à 6 PID
            "03", "07", "0A" -> if (cmd.length != 2) throw ForbiddenCommandException(raw)
            "09" -> if (cmd.length != 4) throw ForbiddenCommandException(raw)
        }
        return cmd
    }
}
