package com.overmind.obdgateway.telemetry

/** Message prêt à publier, stocké tel quel dans l'outbox (rejeu identique => pas de doublon). */
data class OutgoingMessage(
    val messageId: String,
    val kind: String,
    val topic: String,
    val payload: String,
    val qos: Int = 1,
    val retain: Boolean = false,
    val sessionId: String?,
    val sequence: Long,
    val createdAtMs: Long,
)

object Topics {
    private val ident = Regex("^[a-z0-9_\\-]{1,64}$")
    fun isValidId(s: String) = ident.matches(s)
    fun telemetry(vehicle: String) = "overmind/vehicles/$vehicle/obd/telemetry"
    fun dtc(vehicle: String) = "overmind/vehicles/$vehicle/obd/dtc"
    fun status(vehicle: String) = "overmind/vehicles/$vehicle/obd/status"
    fun health(vehicle: String) = "overmind/vehicles/$vehicle/gateway/health"
}

interface SequenceSource { fun next(): Long }
