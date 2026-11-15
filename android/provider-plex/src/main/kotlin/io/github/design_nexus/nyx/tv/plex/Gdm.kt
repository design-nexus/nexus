package io.github.design_nexus.nyx.tv.plex

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Plex's "G'Day Mate" local discovery: a UDP query to port 32414 that servers on the same
 * network answer. Used to find a known server's address when plex.tv can't be reached.
 * On Android the caller must hold a WifiManager.MulticastLock while this runs.
 */
object Gdm {
    data class Found(val id: String, val name: String, val host: String, val port: Int)

    fun parse(text: String, host: String): Found? {
        if (!text.startsWith("HTTP/1.0 200")) return null
        val h = text.lineSequence().mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }.toMap()
        if (h["content-type"]?.contains("plex/media-server") != true) return null
        val id = h["resource-identifier"]?.takeIf { it.isNotEmpty() } ?: return null
        val port = h["port"]?.toIntOrNull() ?: 32400
        return Found(id, h["name"] ?: "Plex server", host, port)
    }

    fun discover(timeoutMs: Int = 1500): List<Found> {
        val found = LinkedHashMap<String, Found>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            val query = "M-SEARCH * HTTP/1.0\r\n\r\n".toByteArray()
            for (target in listOf("239.0.0.250", "255.255.255.255")) {
                runCatching { socket.send(DatagramPacket(query, query.size, InetAddress.getByName(target), 32414)) }
            }
            val buf = ByteArray(2048)
            val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                val remainingMs = ((deadlineNs - System.nanoTime()) / 1_000_000L).toInt()
                if (remainingMs <= 0) break
                socket.soTimeout = remainingMs
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: SocketTimeoutException) { break }
                parse(String(p.data, 0, p.length), p.address.hostAddress ?: continue)?.let { found[it.id] = it }
            }
        }
        return found.values.toList()
    }
}
