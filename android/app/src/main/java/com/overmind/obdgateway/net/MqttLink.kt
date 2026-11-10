package com.overmind.obdgateway.net

import com.overmind.obdgateway.telemetry.OutgoingMessage

/** Lien MQTT sortant, publication seule (la passerelle ne s'abonne à AUCUN topic). */
interface MqttLink {
    val isConnected: Boolean
    /** Lève une exception si la connexion échoue. */
    suspend fun connect()
    /** Publie un lot en QoS 1 ; renvoie les message_id acquittés (PUBACK reçu). */
    suspend fun publish(batch: List<OutgoingMessage>): Set<String>
    fun disconnect()
}
