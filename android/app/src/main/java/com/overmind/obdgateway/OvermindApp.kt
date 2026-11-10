package com.overmind.obdgateway

import android.app.Application
import com.overmind.obdgateway.buffer.GatewayDatabase
import com.overmind.obdgateway.buffer.SqliteOutbox
import com.overmind.obdgateway.buffer.SqliteSessionStore
import com.overmind.obdgateway.gateway.GatewayController
import com.overmind.obdgateway.net.OutboxDrainer
import com.overmind.obdgateway.net.PahoMqttLink
import com.overmind.obdgateway.settings.PrefsSequence
import com.overmind.obdgateway.settings.SettingsRepository
import com.overmind.obdgateway.telemetry.MessageFactory
import com.overmind.obdgateway.telemetry.VehicleIdentity

/** Graphe d'objets de l'appli (injection manuelle, pas de framework DI : appli légère). */
class OvermindApp : Application() {
    lateinit var settings: SettingsRepository; private set
    lateinit var db: GatewayDatabase; private set
    lateinit var outbox: SqliteOutbox; private set
    lateinit var sessions: SqliteSessionStore; private set
    lateinit var messages: MessageFactory; private set
    lateinit var drainer: OutboxDrainer; private set
    lateinit var controller: GatewayController; private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        db = GatewayDatabase(this)
        outbox = SqliteOutbox(db)
        sessions = SqliteSessionStore(db)
        messages = MessageFactory(
            identity = {
                val s = settings.settings.value
                VehicleIdentity(s.vehicleId, s.gatewayId, s.engineType, s.aspiration, s.mapSensorLocation)
            },
            sequence = PrefsSequence(this),
        )
        drainer = OutboxDrainer(
            outbox = outbox,
            linkFactory = { PahoMqttLink(settings.settings.value, messages.lastWill()) },
            log = { controller.log(it) },
        )
        controller = GatewayController(outbox, sessions, drainer, messages)
    }
}
