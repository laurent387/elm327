package com.overmind.obdgateway.obd.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Base commune aux transports à flux (RFCOMM, TCP). Les sockets Bluetooth n'ont pas de
 * timeout de lecture : on interroge available() avec une courte attente.
 */
abstract class StreamTransport : ObdTransport {
    protected var input: InputStream? = null
    protected var output: OutputStream? = null

    override fun write(bytes: ByteArray) {
        val out = output ?: throw IOException("transport fermé")
        out.write(bytes)
        out.flush()
    }

    override fun read(buffer: ByteArray, timeoutMs: Long): Int {
        val inp = input ?: throw IOException("transport fermé")
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val avail = inp.available()
            if (avail > 0) return inp.read(buffer, 0, minOf(avail, buffer.size))
            if (!isOpen) return -1
            if (System.nanoTime() >= deadline) return 0
            try { Thread.sleep(5) } catch (e: InterruptedException) { throw IOException("interrompu", e) }
        }
    }
}
