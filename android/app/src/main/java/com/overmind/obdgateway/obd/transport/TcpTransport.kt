package com.overmind.obdgateway.obd.transport

import java.net.InetSocketAddress
import java.net.Socket

/** ELM327 Wi-Fi (souvent 192.168.0.10:35000) ou simulateur tools/elm327_sim. */
class TcpTransport(private val host: String, private val port: Int) : StreamTransport() {
    private var socket: Socket? = null
    override val description: String get() = "TCP $host:$port"
    override val isOpen: Boolean get() = socket?.let { it.isConnected && !it.isClosed } ?: false

    override fun open() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 5_000)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
