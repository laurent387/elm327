package com.overmind.obdgateway.settings

enum class NetworkMode { TAILSCALE, DIRECT }
enum class AdapterTransport { BLUETOOTH, TCP }

/**
 * Paramètres de la passerelle. Les valeurs par défaut sont des EXEMPLES clairement identifiés
 * (adresse Tailscale fictive, véhicule d'exemple) à remplacer dans l'écran Paramètres.
 */
data class GatewaySettings(
    val networkMode: NetworkMode = NetworkMode.TAILSCALE,
    val brokerHost: String = "overmind.example.ts.net", // EXEMPLE : nom MagicDNS ou IP 100.x du serveur Overmind
    val brokerPort: Int = 1883,
    val useTls: Boolean = false,
    val username: String = "",
    val password: String = "",
    val caCertPem: String = "",            // CA privée (facultatif) ; vide = magasin système
    val vehicleId: String = "slk_r171",    // EXEMPLE
    val gatewayId: String = "android_01",  // EXEMPLE
    val engineType: String = "petrol",
    val aspiration: String = "unknown",
    val mapSensorLocation: String = "unknown",
    val pollIntervalMs: Long = 500,
    val dtcIntervalS: Long = 300,
    val adapterTransport: AdapterTransport = AdapterTransport.BLUETOOTH,
    val btAddress: String = "",
    val btName: String = "",
    val tcpHost: String = "192.168.0.10",
    val tcpPort: Int = 35000,
    val multiPid: Boolean = true,
) {
    /** URI paho. En mode Internet direct, TLS est obligatoire (pas de MQTT en clair sur Internet). */
    val effectiveTls: Boolean get() = useTls || networkMode == NetworkMode.DIRECT
    val brokerUri: String get() = (if (effectiveTls) "ssl" else "tcp") + "://$brokerHost:$brokerPort"

    fun validate(): List<String> = buildList {
        val ident = Regex("^[a-z0-9_\\-]{1,64}$")
        if (!ident.matches(vehicleId)) add("Identifiant véhicule invalide (a-z, 0-9, _ et -)")
        if (!ident.matches(gatewayId)) add("Identifiant passerelle invalide (a-z, 0-9, _ et -)")
        if (brokerHost.isBlank()) add("Adresse du serveur manquante")
        if (brokerPort !in 1..65535) add("Port invalide")
        if (networkMode == NetworkMode.DIRECT && username.isBlank()) add("Mode Internet direct : identifiant MQTT obligatoire")
        if (pollIntervalMs < 100) add("Fréquence d'acquisition trop élevée (≥ 100 ms)")
        if (adapterTransport == AdapterTransport.BLUETOOTH && btAddress.isBlank()) add("Aucun adaptateur ELM327 sélectionné")
    }
}
