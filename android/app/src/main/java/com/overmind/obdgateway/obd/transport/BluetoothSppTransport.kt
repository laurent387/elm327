package com.overmind.obdgateway.obd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic SPP (RFCOMM). Les clones ELM327 sont capricieux : on essaie dans l'ordre
 * socket sécurisée, socket non sécurisée, puis le canal 1 par réflexion (repli connu pour
 * certains adaptateurs).
 * L'appelant doit avoir vérifié BLUETOOTH_CONNECT (Android 12+).
 */
@SuppressLint("MissingPermission")
class BluetoothSppTransport(
    private val adapter: BluetoothAdapter,
    private val address: String,
) : StreamTransport() {

    private var socket: BluetoothSocket? = null
    override val description: String get() = "Bluetooth $address"
    override val isOpen: Boolean get() = socket?.isConnected == true

    override fun open() {
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() } // la découverte ralentit fortement RFCOMM
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "secure" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "channel1" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for ((name, factory) in attempts) {
            try {
                val s = factory()
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                Log.i(TAG, "SPP connecté ($name) à $address")
                return
            } catch (e: Exception) {
                Log.w(TAG, "SPP $name échoué : ${e.message}")
                last = e
                runCatching { socket?.close() }
                socket = null
            }
        }
        throw IOException("Connexion SPP impossible à $address", last)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    companion object {
        private const val TAG = "SppTransport"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
