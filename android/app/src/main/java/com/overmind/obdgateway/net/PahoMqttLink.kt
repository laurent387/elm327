package com.overmind.obdgateway.net

import com.overmind.obdgateway.settings.GatewaySettings
import com.overmind.obdgateway.telemetry.OutgoingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Client MQTT Eclipse Paho (Java pur, léger). TLS avec vérification stricte du certificat
 * et du nom d'hôte ; CA privée facultative (broker Tailscale avec certificat auto-signé).
 * La fiabilité ne repose pas sur la session MQTT mais sur l'outbox SQLite + message_id.
 */
class PahoMqttLink(
    private val settings: GatewaySettings,
    private val lastWill: Pair<String, String>?,
) : MqttLink {

    private var client: MqttAsyncClient? = null

    override val isConnected: Boolean get() = client?.isConnected == true

    override suspend fun connect() = withContext(Dispatchers.IO) {
        disconnect()
        val c = MqttAsyncClient(settings.brokerUri, settings.gatewayId, MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = false // reconnexion pilotée par OutboxDrainer (backoff + réseau)
            connectionTimeout = 10
            keepAliveInterval = 30
            maxInflight = 64
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            if (settings.username.isNotBlank()) userName = settings.username
            if (settings.password.isNotEmpty()) password = settings.password.toCharArray()
            if (settings.effectiveTls) {
                socketFactory = tlsSocketFactory(settings.caCertPem)
                isHttpsHostnameVerificationEnabled = true
            }
            lastWill?.let { (topic, payload) -> setWill(topic, payload.toByteArray(), 1, true) }
        }
        try {
            c.connect(opts).waitForCompletion(15_000)
        } catch (e: Exception) {
            runCatching { c.close(true) }
            throw e
        }
        client = c
    }

    override suspend fun publish(batch: List<OutgoingMessage>): Set<String> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext emptySet()
        val tokens = ArrayList<Pair<String, IMqttDeliveryToken>>(batch.size)
        for (m in batch) {
            val msg = MqttMessage(m.payload.toByteArray(Charsets.UTF_8)).apply { qos = m.qos; isRetained = m.retain }
            tokens += m.messageId to c.publish(m.topic, msg)
        }
        val acked = HashSet<String>()
        for ((id, t) in tokens) {
            runCatching { t.waitForCompletion(10_000) }
            if (t.isComplete && t.exception == null) acked += id
        }
        acked
    }

    override fun disconnect() {
        val c = client ?: return
        client = null
        runCatching { if (c.isConnected) c.disconnect(2_000).waitForCompletion(3_000) }
        runCatching { c.close(true) }
    }

    companion object {
        fun tlsSocketFactory(caPem: String): SocketFactory {
            val ctx = SSLContext.getInstance("TLS")
            if (caPem.isBlank()) {
                ctx.init(null, null, null) // magasin de confiance système
            } else {
                val cf = CertificateFactory.getInstance("X.509")
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
                cf.generateCertificates(ByteArrayInputStream(caPem.toByteArray())).forEachIndexed { i, cert ->
                    ks.setCertificateEntry("overmind_ca_$i", cert)
                }
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
                ctx.init(null, tmf.trustManagers, null)
            }
            return ctx.socketFactory
        }
    }
}
