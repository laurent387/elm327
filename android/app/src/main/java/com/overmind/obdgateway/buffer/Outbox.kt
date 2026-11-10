package com.overmind.obdgateway.buffer

import com.overmind.obdgateway.telemetry.OutgoingMessage

/**
 * Tampon local persistant. Tout message passe par l'outbox AVANT publication ; il n'en sort
 * qu'après accusé QoS 1 (PUBACK). Le rejeu après coupure renvoie le même message_id,
 * dédupliqué par le serveur.
 */
interface Outbox {
    fun enqueue(m: OutgoingMessage)
    /** Les plus anciens d'abord (ordre de séquence). */
    fun peek(limit: Int): List<OutgoingMessage>
    fun remove(messageIds: Collection<String>)
    fun count(): Int
    fun countForSession(sessionId: String): Int
    /** Limite la taille : supprime la télémétrie la plus ancienne au-delà de [maxRows] (jamais DTC/status). */
    fun trim(maxRows: Int): Int
}

class InMemoryOutbox : Outbox {
    private val items = ArrayList<OutgoingMessage>()
    @Synchronized override fun enqueue(m: OutgoingMessage) { if (items.none { it.messageId == m.messageId }) items += m }
    @Synchronized override fun peek(limit: Int) = items.sortedBy { it.sequence }.take(limit)
    @Synchronized override fun remove(messageIds: Collection<String>) { items.removeAll { it.messageId in messageIds } }
    @Synchronized override fun count() = items.size
    @Synchronized override fun countForSession(sessionId: String) = items.count { it.sessionId == sessionId }
    @Synchronized override fun trim(maxRows: Int): Int {
        var excess = items.size - maxRows
        if (excess <= 0) return 0
        val victims = items.filter { it.kind == "telemetry" }.sortedBy { it.sequence }.take(excess)
        items.removeAll(victims.toSet())
        return victims.size
    }
}
