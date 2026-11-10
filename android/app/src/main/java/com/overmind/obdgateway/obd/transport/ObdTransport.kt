package com.overmind.obdgateway.obd.transport

import java.io.Closeable
import java.io.IOException

/**
 * Lien série brut vers un ELM327 (Bluetooth SPP, TCP pour ELM Wi-Fi / simulateur, ou faux en test).
 * Les méthodes sont bloquantes et appelées depuis Dispatchers.IO.
 */
interface ObdTransport : Closeable {
    val description: String
    @Throws(IOException::class) fun open()
    @Throws(IOException::class) fun write(bytes: ByteArray)
    /** Lit au plus [buffer].size octets ; attend au plus [timeoutMs]. Renvoie 0 si rien n'est arrivé, -1 si le flux est fermé. */
    @Throws(IOException::class) fun read(buffer: ByteArray, timeoutMs: Long): Int
    val isOpen: Boolean
}
