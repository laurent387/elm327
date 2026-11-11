package com.overmind.copilot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Protocole autoradio ↔ Overmind (schemas/COPILOT_PROTOCOL.md). Code pur, testé sur la JVM. */
object Protocol {
    const val SAMPLE_RATE = 16_000
    private val json = Json { ignoreUnknownKeys = true }

    fun hello(token: String, deviceId: String, vehicleId: String, appVersion: String, remote: Boolean = false,
              pairingCode: String = ""): String = buildJsonObject {
        put("type", "hello")
        // Pas encore de jeton : code d'appairage à 6 chiffres, échangé contre un jeton (message `paired`).
        if (token.isEmpty() && pairingCode.isNotEmpty()) put("pairing_code", pairingCode) else put("token", token)
        put("device_id", deviceId)
        put("vehicle_id", vehicleId); put("app_version", appVersion); put("remote", remote)
    }.toString()

    /** Réponse à une commande de télécommande ([ServerMessage.Remote]). */
    fun remoteResult(id: String, data: JsonObject?, error: String? = null): String = buildJsonObject {
        put("type", "remote_result"); put("id", id); put("ok", error == null)
        if (error == null) put("data", data ?: JsonObject(emptyMap())) else put("error", error.take(300))
    }.toString()

    fun utteranceStart(id: String, wake: Boolean, context: JsonObject): String = buildJsonObject {
        put("type", "utterance_start"); put("id", id); put("sample_rate", SAMPLE_RATE); put("wake", wake)
        put("context", context)
    }.toString()

    fun utteranceEnd(id: String): String = buildJsonObject { put("type", "utterance_end"); put("id", id) }.toString()

    fun text(id: String, text: String, context: JsonObject): String = buildJsonObject {
        put("type", "text"); put("id", id); put("text", text); put("context", context)
    }.toString()

    fun actionResult(id: String, ok: Boolean, detail: String): String = buildJsonObject {
        put("type", "action_result"); put("id", id); put("ok", ok); put("detail", detail.take(200))
    }.toString()

    /** Mise à jour tirée par l'autoradio : demande le morceau suivant de l'APK préparé. */
    fun updateGet(nonce: String, offset: Long): String = buildJsonObject {
        put("type", "update_get"); put("id", nonce); put("offset", offset)
    }.toString()

    fun parse(raw: String): ServerMessage {
        val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return ServerMessage.Unknown("?")
        val id = o.str("id") ?: ""
        return when (o.str("type")) {
            "welcome" -> ServerMessage.Welcome(o.str("assistant") ?: "Kitt", o.bool("stt") ?: true, o.bool("tts") ?: true)
            "transcript" -> ServerMessage.Transcript(id, o.str("text") ?: "")
            "ignored" -> ServerMessage.Ignored(id)
            "reply" -> ServerMessage.Reply(id, o.str("text") ?: "", o.str("intent") ?: "chat", ClientAction.from(o["action"]))
            "audio" -> ServerMessage.Audio(id, o["bytes"]?.jsonPrimitive?.intOrNull ?: 0)
            "done" -> ServerMessage.Done(id, o["timings"]?.let { t ->
                runCatching { t.jsonObject["total_ms"]?.jsonPrimitive?.intOrNull }.getOrNull() } ?: 0)
            "error" -> ServerMessage.Error(id, o.str("code") ?: "error", o.str("message") ?: "")
            "pong" -> ServerMessage.Pong
            "paired" -> o.str("token")?.takeIf { it.length >= 20 }?.let { ServerMessage.Paired(it) } ?: ServerMessage.Unknown("paired")
            "update_data" -> ServerMessage.UpdateData(id, o["offset"]?.jsonPrimitive?.longOrNull ?: -1,
                o["bytes"]?.jsonPrimitive?.intOrNull ?: 0, o.bool("eof") ?: false, o.str("error"))
            "remote" -> ServerMessage.Remote(id, RemoteCommand.from(o.str("cmd")), o.str("cmd") ?: "?",
                (o["args"] as? JsonObject) ?: JsonObject(emptyMap()))
            else -> ServerMessage.Unknown(o.str("type") ?: "?")
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(k: String) = (this[k] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull
}

sealed class ServerMessage {
    data class Welcome(val assistant: String, val stt: Boolean, val tts: Boolean) : ServerMessage()
    data class Transcript(val id: String, val text: String) : ServerMessage()
    data class Ignored(val id: String) : ServerMessage()
    data class Reply(val id: String, val text: String, val intent: String, val action: ClientAction?) : ServerMessage()
    data class Audio(val id: String, val bytes: Int) : ServerMessage()
    data class Done(val id: String, val totalMs: Int) : ServerMessage()
    data class Error(val id: String, val code: String, val message: String) : ServerMessage()
    data object Pong : ServerMessage()
    /** Appairage accepté : jeton propre à cet autoradio (à enregistrer, jamais affiché). */
    data class Paired(val token: String) : ServerMessage()
    /** Télécommande depuis Overmind ; [command] = null si hors de la liste fermée (refusée). */
    /** Annonce le morceau binaire d'APK qui suit (sauf si [error]). */
    data class UpdateData(val id: String, val offset: Long, val bytes: Int, val eof: Boolean, val error: String?) : ServerMessage()
    data class Remote(val id: String, val command: RemoteCommand?, val raw: String, val args: JsonObject) : ServerMessage()
    data class Unknown(val type: String) : ServerMessage()
}

/**
 * Actions que l'autoradio accepte d'exécuter. Liste FERMÉE : tout autre type est ignoré.
 * Aucune ne concerne le véhicule (le bus OBD n'est jamais piloté depuis le réseau).
 */
sealed class ClientAction {
    data class Media(val command: MediaCommand) : ClientAction()
    data class Navigate(val destination: String) : ClientAction()
    data class Call(val contact: String) : ClientAction()

    companion object {
        fun from(e: JsonElement?): ClientAction? {
            val o = (e as? JsonObject) ?: return null
            fun s(k: String) = (o[k] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.trim()
            return when (s("type")) {
                "media" -> MediaCommand.from(s("command"))?.let { Media(it) }
                "navigate" -> s("destination")?.takeIf { it.isNotEmpty() }?.let { Navigate(it.take(200)) }
                "call" -> s("contact")?.takeIf { it.isNotEmpty() }?.let { Call(it.take(80)) }
                else -> null
            }
        }
    }
}

/**
 * Commandes de télécommande acceptées par l'autoradio. Liste FERMÉE, identique à
 * overmind_copilot/remote.py : aucune ne touche le véhicule (pas d'OBD, pas de shell).
 */
enum class RemoteCommand(val wire: String, val readOnly: Boolean) {
    STATUS("status", true), DISPLAY("display", true), LOGS("logs", true), SCREENSHOT("screenshot", true),
    LIST_APPS("list_apps", true), MEDIA("media", false), LAUNCH_APP("launch_app", false),
    RESTART("restart", false), UPDATE("update", false);

    companion object { fun from(s: String?) = entries.firstOrNull { it.wire == s } }
}

/** Identifiant d'une mise à jour préparée par Overmind (transmise dans la WebSocket, jamais une URL). */
object UpdateNonce {
    private val RE = Regex("^[A-Za-z0-9_-]{16,64}$")
    fun valid(s: String?) = s != null && RE.matches(s)
}

enum class MediaCommand(val wire: String) {
    PLAY("play"), PAUSE("pause"), TOGGLE("toggle"), NEXT("next"), PREVIOUS("previous"),
    VOLUME_UP("volume_up"), VOLUME_DOWN("volume_down");

    companion object { fun from(s: String?) = entries.firstOrNull { it.wire == s } }
}
