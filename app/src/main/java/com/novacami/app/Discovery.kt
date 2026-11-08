package com.novacami.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/** Broadcasts "NOVACAMI|name|port" on the LAN every 2s so the PC app can find this phone. */
class Discovery(private val port: Int, private val name: String) {
    @Volatile private var running = false

    fun start() {
        running = true
        thread(name = "discovery") {
            val msg = "NOVACAMI|$name|$port".toByteArray()
            val sock = DatagramSocket().apply { broadcast = true }
            while (running) {
                try {
                    val targets = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
                    NetworkInterface.getNetworkInterfaces().toList()
                        .filter { it.isUp && !it.isLoopback }
                        .forEach { ni -> ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { targets.add(it) } } }
                    targets.forEach {
                        try { sock.send(DatagramPacket(msg, msg.size, it, 48555)) } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { }
            }
            sock.close()
        }
    }

    fun stop() { running = false }
}
