package com.overmind.obdgateway.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L'effacement exige une session active, véhicule à l'arrêt et moteur coupé, mesurés récemment. */
class ClearBlockerTest {
    private val now = 1_000_000L
    private fun v(key: String, value: Double?, status: String = "ok", age: Long = 500) =
        key to LiveValue(null, key, key, "", value, status, "7E8", now - age)

    private fun state(obd: ObdState = ObdState.RUNNING, vararg live: Pair<String, LiveValue>) =
        GatewayUiState(obdState = obd, live = mapOf(*live))

    @Test fun allowedEngineOffStopped() =
        assertNull(clearBlocker(state(ObdState.RUNNING, v("speed_kmh", 0.0), v("rpm", 0.0)), now))

    @Test fun refusals() {
        assertEquals("aucune session OBD en cours", clearBlocker(state(ObdState.IDLE, v("speed_kmh", 0.0), v("rpm", 0.0)), now))
        assertEquals("le véhicule roule", clearBlocker(state(ObdState.RUNNING, v("speed_kmh", 12.0), v("rpm", 0.0)), now))
        assertEquals(true, clearBlocker(state(ObdState.RUNNING, v("speed_kmh", 0.0), v("rpm", 780.0)), now)!!.startsWith("le moteur tourne"))
        assertEquals("vitesse du véhicule inconnue", clearBlocker(state(ObdState.RUNNING, v("rpm", 0.0)), now))
        assertEquals("régime moteur inconnu", clearBlocker(state(ObdState.RUNNING, v("speed_kmh", 0.0), v("rpm", null, "no_data")), now))
        // mesures trop anciennes : on ne se fie pas à un 0 périmé
        assertEquals("vitesse du véhicule inconnue",
            clearBlocker(state(ObdState.RUNNING, v("speed_kmh", 0.0, age = 60_000), v("rpm", 0.0)), now))
    }
}
