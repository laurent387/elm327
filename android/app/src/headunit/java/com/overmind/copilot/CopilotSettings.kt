package com.overmind.copilot

import android.content.Context
import com.overmind.obdgateway.settings.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CopilotConfig(
    /** Ex. wss://88.175.164.245:38097/copilot (Internet, redirection Freebox, CA privée) ; ws://127.0.0.1:8097/copilot avec adb reverse. */
    val serverUrl: String = "",
    val token: String = "",
    /** Mot tel que le modèle Vosk l'écrit (« Kitt » → « kit »). */
    val wakeWord: String = "kit",
    val wakeEnabled: Boolean = true,
    val deviceId: String = "headunit-01",
    /** Télécommande depuis Overmind (état, journaux, capture, musique, mise à jour confirmée à l'écran). */
    val remoteEnabled: Boolean = true,
    /** Code d'appairage à 6 chiffres (à usage unique) : échangé contre un jeton à la connexion. */
    val pairingCode: String = "",
) {
    val configured get() = serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://")
}

/** Réglages du copilote ; le jeton est chiffré par l'Android Keystore (comme le mot de passe MQTT). */
class CopilotSettings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("copilot_settings", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<CopilotConfig> = _config.asStateFlow()

    private fun load() = CopilotConfig(
        serverUrl = prefs.getString("serverUrl", "")!!,
        token = SecretStore.decrypt(prefs.getString("tokenEnc", "")!!),
        wakeWord = prefs.getString("wakeWord", "kit")!!,
        wakeEnabled = prefs.getBoolean("wakeEnabled", true),
        deviceId = prefs.getString("deviceId", "headunit-01")!!,
        remoteEnabled = prefs.getBoolean("remoteEnabled", true),
        pairingCode = prefs.getString("pairingCode", "")!!,
    )

    /** Le champ « jeton » accepte aussi un code d'appairage : 6 chiffres = code, sinon jeton. */
    fun saveFromForm(c: CopilotConfig, tokenOrCode: String) {
        val v = tokenOrCode.trim()
        save(when {
            PAIRING_CODE.matches(v) -> c.copy(pairingCode = v, token = "")
            v.isNotEmpty() -> c.copy(token = v, pairingCode = "")
            else -> c
        })
    }

    /** Appairage réussi : le jeton reçu remplace le code. */
    fun savePairedToken(token: String) = save(config.value.copy(token = token, pairingCode = ""))

    fun clearPairingCode() = save(config.value.copy(pairingCode = ""))

    fun save(c: CopilotConfig) {
        prefs.edit()
            .putString("serverUrl", c.serverUrl.trim())
            .putString("tokenEnc", SecretStore.encrypt(c.token.trim()))
            .putString("wakeWord", c.wakeWord.trim().lowercase())
            .putBoolean("wakeEnabled", c.wakeEnabled)
            .putString("deviceId", c.deviceId.trim())
            .putBoolean("remoteEnabled", c.remoteEnabled)
            .putString("pairingCode", c.pairingCode)
            .apply()
        _config.value = c.copy(serverUrl = c.serverUrl.trim(), token = c.token.trim(),
            wakeWord = c.wakeWord.trim().lowercase())
    }
}

val PAIRING_CODE = Regex("^\\d{6}$")
