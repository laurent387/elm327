package com.overmind.obdgateway.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleMovingTest {
    private val now = 1_000_000L

    private fun state(obd: ObdState, kmh: Double?, status: String = "ok", ageMs: Long = 500) = GatewayUiState(
        obdState = obd,
        live = mapOf("speed_kmh" to LiveValue("010D", "speed_kmh", "Vitesse", "km/h", kmh, status, "7E8", now - ageMs)),
    )

    @Test fun movingDuringSession() = assertTrue(state(ObdState.RUNNING, 29.0).vehicleMoving(now))

    @Test fun stillDuringSession() = assertFalse(state(ObdState.RUNNING, 0.0).vehicleMoving(now))

    @Test fun stoppedSessionDoesNotLockOnLastSpeed() {
        assertFalse(state(ObdState.STOPPED, 29.0).vehicleMoving(now))
        assertFalse(state(ObdState.IDLE, 29.0).vehicleMoving(now))
    }

    @Test fun staleSpeedDoesNotLock() {
        assertFalse(state(ObdState.RECONNECTING, 29.0, ageMs = 60_000).vehicleMoving(now))
        assertFalse(state(ObdState.RUNNING, null, status = "no_data").vehicleMoving(now))
    }

    @Test fun noSpeedReading() = assertFalse(GatewayUiState(obdState = ObdState.RUNNING).vehicleMoving(now))
}
